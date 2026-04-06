package com.copytrading.child;

import com.copytrading.auth.UserAccountRepository;
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
public class ChildService {

    private final SubscriptionRepository subs;
    private final UserAccountRepository users;
    private final TradeLogRepository logs;

    public ChildService(SubscriptionRepository subs, UserAccountRepository users, TradeLogRepository logs) {
        this.subs = subs;
        this.users = users;
        this.logs = logs;
    }

    // 5.1 List available masters
    public Mono<Map<String, Object>> listMasters() {
        return users.findByRole("MASTER").collectList().map(masters -> {
            var list = masters.stream().map(m -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("masterId", m.getId());
                r.put("name", m.getName());
                r.put("winRate", 0);
                r.put("totalTrades", 0);
                r.put("avgPnl", 0);
                r.put("subscribers", 0);
                return r;
            }).toList();
            return Map.<String, Object>of("masters", list);
        });
    }

    // 5.2 Subscribe to master
    // New child → PENDING_APPROVAL (master must approve)
    // Previously approved child (unsubscribed & re-subscribing) → ACTIVE directly
    public Mono<Map<String, Object>> subscribe(UUID childId, UUID masterId, UUID brokerAccountId, Double scalingFactor) {
        double factor = (scalingFactor != null && scalingFactor >= 0.01 && scalingFactor <= 10.0) ? scalingFactor : 1.0;
        return subs.findByMasterIdAndChildId(masterId, childId)
                .flatMap(existing -> {
                    // If already exists and active/paused → conflict
                    if ("ACTIVE".equals(existing.getCopyingStatus()) || "PAUSED".equals(existing.getCopyingStatus())
                            || "PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                        return Mono.<Map<String, Object>>error(
                                new ResponseStatusException(HttpStatus.CONFLICT, "Already subscribed or pending approval"));
                    }
                    // If previously approved (INACTIVE/REJECTED but approvedOnce=true) → reactivate directly
                    if (existing.isApprovedOnce()) {
                        existing.setCopyingStatus("ACTIVE");
                        existing.setBrokerAccountId(brokerAccountId);
                        existing.setCreatedAt(Instant.now());
                        return subs.save(existing).map(saved -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("subscriptionId", saved.getId());
                            r.put("status", "ACTIVE");
                            r.put("message", "Re-subscribed successfully (previously approved)");
                            return r;
                        });
                    }
                    // Never approved before → set to pending
                    existing.setCopyingStatus("PENDING_APPROVAL");
                    existing.setBrokerAccountId(brokerAccountId);
                    existing.setCreatedAt(Instant.now());
                    return subs.save(existing).map(saved -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("subscriptionId", saved.getId());
                        r.put("status", "PENDING_APPROVAL");
                        r.put("message", "Subscription request sent. Waiting for master approval.");
                        return r;
                    });
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Brand new subscription → PENDING_APPROVAL
                    Subscription s = new Subscription();
                    s.setMasterId(masterId);
                    s.setChildId(childId);
                    s.setBrokerAccountId(brokerAccountId);
                    s.setScalingFactor(factor);
                    s.setCopyingStatus("PENDING_APPROVAL");
                    s.setApprovedOnce(false);
                    s.setCreatedAt(Instant.now());
                    return subs.save(s).map(saved -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("subscriptionId", saved.getId());
                        r.put("status", "PENDING_APPROVAL");
                        r.put("message", "Subscription request sent. Waiting for master approval.");
                        return r;
                    });
                }));
    }

    // 5.2b Bulk subscribe to multiple masters (with approval logic)
    public Mono<Map<String, Object>> bulkSubscribe(UUID childId, List<Map<String, Object>> masters) {
        return reactor.core.publisher.Flux.fromIterable(masters)
                .flatMap(m -> {
                    UUID masterId = UUID.fromString(m.get("masterId").toString());
                    UUID brokerAccountId = m.containsKey("brokerAccountId") && m.get("brokerAccountId") != null
                            ? UUID.fromString(m.get("brokerAccountId").toString()) : null;
                    double factor = m.containsKey("scalingFactor") && m.get("scalingFactor") != null
                            ? ((Number) m.get("scalingFactor")).doubleValue() : 1.0;
                    return subs.findByMasterIdAndChildId(masterId, childId)
                            .flatMap(existing -> {
                                if ("ACTIVE".equals(existing.getCopyingStatus()) || "PAUSED".equals(existing.getCopyingStatus())
                                        || "PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                                    return Mono.just(Map.<String, Object>of("masterId", masterId.toString(), "status", "ALREADY_SUBSCRIBED"));
                                }
                                // INACTIVE/REJECTED — re-subscribe
                                if (existing.isApprovedOnce()) {
                                    existing.setCopyingStatus("ACTIVE");
                                    existing.setBrokerAccountId(brokerAccountId);
                                    return subs.save(existing).map(s -> Map.<String, Object>of(
                                            "masterId", masterId.toString(), "status", "RE_SUBSCRIBED", "subscriptionId", s.getId()));
                                }
                                existing.setCopyingStatus("PENDING_APPROVAL");
                                existing.setBrokerAccountId(brokerAccountId);
                                return subs.save(existing).map(s -> Map.<String, Object>of(
                                        "masterId", masterId.toString(), "status", "PENDING_APPROVAL", "subscriptionId", s.getId()));
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                Subscription s = new Subscription();
                                s.setMasterId(masterId);
                                s.setChildId(childId);
                                s.setBrokerAccountId(brokerAccountId);
                                s.setScalingFactor(factor);
                                s.setCopyingStatus("PENDING_APPROVAL");
                                s.setApprovedOnce(false);
                                s.setCreatedAt(Instant.now());
                                return subs.save(s).map(saved -> Map.<String, Object>of(
                                        "masterId", masterId.toString(), "status", "PENDING_APPROVAL", "subscriptionId", saved.getId()));
                            }));
                })
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    // 5.3 Unsubscribe (set INACTIVE, keep record for re-subscribe auto-approval)
    public Mono<Map<String, String>> unsubscribe(UUID childId, UUID masterId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    s.setCopyingStatus("INACTIVE");
                    return subs.save(s).thenReturn(Map.of("message", "Unsubscribed"));
                });
    }

    // 5.4 List subscriptions (exclude INACTIVE)
    public Mono<Map<String, Object>> listSubscriptions(UUID childId) {
        return subs.findByChildIdAndCopyingStatusNot(childId, "INACTIVE")
                .flatMap(s -> users.findById(s.getMasterId()).map(m -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("masterId", s.getMasterId());
                    r.put("masterName", m.getName());
                    r.put("scalingFactor", s.getScalingFactor());
                    r.put("copyingStatus", s.getCopyingStatus());
                    r.put("subscribedAt", s.getCreatedAt());
                    r.put("brokerAccountId", s.getBrokerAccountId());
                    return r;
                }))
                .collectList()
                .map(list -> Map.<String, Object>of("subscriptions", list));
    }

    // 5.5 Get scaling
    public Mono<Map<String, Object>> getScaling(UUID childId, UUID masterId) {
        if (masterId != null) {
            return subs.findByMasterIdAndChildId(masterId, childId)
                    .map(s -> Map.<String, Object>of("scalingFactor", s.getScalingFactor()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")));
        }
        return subs.findByChildId(childId).next()
                .map(s -> Map.<String, Object>of("scalingFactor", s.getScalingFactor()))
                .switchIfEmpty(Mono.just(Map.of("scalingFactor", 1.0)));
    }

    // 5.6 Update scaling
    public Mono<Map<String, Object>> updateScaling(UUID childId, UUID masterId, double factor) {
        if (factor < 0.01 || factor > 10.0)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scaling factor must be 0.01 to 10.0"));
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> { s.setScalingFactor(factor); return subs.save(s); })
                .map(s -> Map.<String, Object>of("scalingFactor", s.getScalingFactor()));
    }

    // 5.7 Pause copying
    public Mono<Map<String, String>> pauseCopying(UUID childId, UUID masterId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> { s.setCopyingStatus("PAUSED"); return subs.save(s); })
                .thenReturn(Map.of("message", "Copying paused"));
    }

    // 5.8 Resume copying
    public Mono<Map<String, String>> resumeCopying(UUID childId, UUID masterId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> { s.setCopyingStatus("ACTIVE"); return subs.save(s); })
                .thenReturn(Map.of("message", "Copying resumed"));
    }

    // 5.9 Copied trades
    public Mono<Map<String, Object>> getCopiedTrades(UUID childId) {
        return logs.findByChildId(childId).collectList()
                .map(list -> Map.<String, Object>of("trades", list));
    }

    // 5.10 Child analytics
    public Mono<Map<String, Object>> getAnalytics(UUID childId) {
        return logs.findByChildId(childId).collectList().map(tradeLogs -> {
            long copied = tradeLogs.stream().filter(l -> "REPLICATED".equals(l.getType())).count();
            long failed = tradeLogs.stream().filter(l -> "FAILED".equals(l.getStatus())).count();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("totalPnl", 0);
            r.put("copiedTrades", copied);
            r.put("failedReplications", failed);
            r.put("masterPnlComparison", Map.of());
            return r;
        });
    }
}
