package com.copytrading.master;

import com.copytrading.auth.UserAccountRepository;
import com.copytrading.auth.dto.UserDto;
import com.copytrading.logs.TradeLogRepository;
import com.copytrading.subscription.Subscription;
import com.copytrading.subscription.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
public class MasterService {

    private final SubscriptionRepository subs;
    private final UserAccountRepository users;
    private final TradeLogRepository logs;

    public MasterService(SubscriptionRepository subs, UserAccountRepository users, TradeLogRepository logs) {
        this.subs = subs;
        this.users = users;
        this.logs = logs;
    }

    // 4.1 List children
    public Mono<Map<String, Object>> listChildren(UUID masterId) {
        return subs.findByMasterId(masterId)
                .flatMap(s -> users.findById(s.getChildId()).map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("childId", s.getChildId());
                    m.put("name", u.getName());
                    m.put("email", u.getEmail());
                    m.put("scalingFactor", s.getScalingFactor());
                    m.put("copyingStatus", s.getCopyingStatus());
                    m.put("subscribedAt", s.getCreatedAt());
                    return m;
                }))
                .collectList()
                .map(list -> Map.of("children", (Object) list));
    }

    // 4.2 Link child (master-initiated, bypasses approval)
    public Mono<Map<String, String>> linkChild(UUID masterId, UUID childId, Double scalingFactor) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .flatMap(existing -> {
                    // If pending, approve it
                    if ("PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                        existing.setCopyingStatus("ACTIVE");
                        existing.setApprovedOnce(true);
                        if (scalingFactor != null) existing.setScalingFactor(scalingFactor);
                        return subs.save(existing).thenReturn(Map.of("message", "Child approved and linked"));
                    }
                    return Mono.<Map<String, String>>error(
                            new ResponseStatusException(HttpStatus.CONFLICT, "Child already linked"));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Subscription s = new Subscription();
                    s.setMasterId(masterId);
                    s.setChildId(childId);
                    s.setScalingFactor(scalingFactor != null ? scalingFactor : 1.0);
                    s.setCopyingStatus("ACTIVE");
                    s.setApprovedOnce(true);
                    s.setCreatedAt(Instant.now());
                    return subs.save(s).thenReturn(Map.of("message", "Child linked successfully"));
                }));
    }

    // 4.2a List pending approval requests
    public Mono<Map<String, Object>> listPendingApprovals(UUID masterId) {
        return subs.findByMasterIdAndCopyingStatus(masterId, "PENDING_APPROVAL")
                .flatMap(s -> users.findById(s.getChildId()).map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("childId", s.getChildId());
                    m.put("name", u.getName());
                    m.put("email", u.getEmail());
                    m.put("requestedAt", s.getCreatedAt());
                    m.put("subscriptionId", s.getId());
                    return m;
                }))
                .collectList()
                .map(list -> Map.of("pendingApprovals", (Object) list));
    }

    // 4.2b Approve child subscription
    public Mono<Map<String, String>> approveChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    if (!"PENDING_APPROVAL".equals(s.getCopyingStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscription is not pending approval"));
                    }
                    s.setCopyingStatus("ACTIVE");
                    s.setApprovedOnce(true);
                    return subs.save(s).thenReturn(Map.of("message", "Child approved"));
                });
    }

    // 4.2c Reject child subscription
    public Mono<Map<String, String>> rejectChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    if (!"PENDING_APPROVAL".equals(s.getCopyingStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscription is not pending approval"));
                    }
                    s.setCopyingStatus("REJECTED");
                    return subs.save(s).thenReturn(Map.of("message", "Child rejected"));
                });
    }

    // 4.2b Bulk link children
    public Mono<Map<String, Object>> bulkLinkChildren(UUID masterId, List<Map<String, Object>> children) {
        return reactor.core.publisher.Flux.fromIterable(children)
                .flatMap(c -> {
                    UUID childId = UUID.fromString(c.get("childId").toString());
                    Double factor = c.containsKey("scalingFactor") && c.get("scalingFactor") != null
                            ? ((Number) c.get("scalingFactor")).doubleValue() : 1.0;
                    return subs.findByMasterIdAndChildId(masterId, childId)
                            .map(existing -> Map.<String, Object>of("childId", childId.toString(), "status", "ALREADY_LINKED"))
                            .switchIfEmpty(Mono.defer(() -> {
                                Subscription s = new Subscription();
                                s.setMasterId(masterId);
                                s.setChildId(childId);
                                s.setScalingFactor(factor);
                                s.setCopyingStatus("ACTIVE");
                                s.setCreatedAt(Instant.now());
                                return subs.save(s).map(saved -> Map.<String, Object>of(
                                        "childId", childId.toString(), "status", "LINKED", "subscriptionId", saved.getId()));
                            }));
                })
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    // 4.2c Master subscribes to a child (master follows child's trades)
    public Mono<Map<String, Object>> subscribeToChild(UUID masterId, UUID childId, Double scalingFactor) {
        // Here the master becomes the follower: masterId in child_id column, childId in master_id column
        // This means the "child" user is the trade source, and the "master" user copies their trades
        return subs.findByMasterIdAndChildId(childId, masterId)
                .flatMap(e -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Already subscribed to this child")))
                .switchIfEmpty(Mono.defer(() -> {
                    Subscription s = new Subscription();
                    s.setMasterId(childId);   // child is the trade source
                    s.setChildId(masterId);   // master is the follower
                    s.setScalingFactor(scalingFactor != null ? scalingFactor : 1.0);
                    s.setCopyingStatus("ACTIVE");
                    s.setCreatedAt(Instant.now());
                    return subs.save(s).map(saved -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("subscriptionId", saved.getId());
                        r.put("message", "Subscribed to child successfully");
                        return r;
                    });
                }));
    }

    // 4.3 Unlink child
    public Mono<Map<String, String>> unlinkChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .flatMap(s -> subs.delete(s).thenReturn(Map.of("message", "Child unlinked")));
    }

    // 4.4 Get scaling
    public Mono<Map<String, Object>> getScaling(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .map(s -> Map.<String, Object>of("childId", childId, "scalingFactor", s.getScalingFactor()));
    }

    // 4.5 Update scaling
    public Mono<Map<String, Object>> updateScaling(UUID masterId, UUID childId, double factor) {
        if (factor < 0.01 || factor > 10.0)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scaling factor must be 0.01 to 10.0"));
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .flatMap(s -> { s.setScalingFactor(factor); return subs.save(s); })
                .map(s -> Map.<String, Object>of("childId", childId, "scalingFactor", s.getScalingFactor()));
    }

    // 4.6 Master analytics
    public Mono<Map<String, Object>> getAnalytics(UUID masterId) {
        return Mono.zip(
                logs.findByMasterId(masterId).collectList(),
                subs.findByMasterId(masterId).collectList()
        ).map(t -> {
            var tradeLogs = t.getT1();
            var children = t.getT2();
            long totalTrades = tradeLogs.stream().filter(l -> "EXECUTED".equals(l.getStatus())).count();
            long totalReplications = tradeLogs.stream().filter(l -> "REPLICATED".equals(l.getType())).count();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("totalPnl", 0);
            r.put("winRate", 0);
            r.put("totalTrades", totalTrades);
            r.put("totalReplications", totalReplications);
            r.put("childPerformance", children.stream().map(s -> Map.of(
                    "childId", s.getChildId(), "scalingFactor", s.getScalingFactor(),
                    "copyingStatus", s.getCopyingStatus())).toList());
            return r;
        });
    }

    // 4.7 Trade history
    public Mono<Map<String, Object>> getTradeHistory(UUID masterId) {
        return logs.findByMasterId(masterId).collectList()
                .map(list -> Map.<String, Object>of("trades", list));
    }
}
