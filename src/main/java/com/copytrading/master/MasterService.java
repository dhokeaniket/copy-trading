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

    // 4.2 Link child
    public Mono<Map<String, String>> linkChild(UUID masterId, UUID childId, Double scalingFactor) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .flatMap(existing -> Mono.<Map<String, String>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Child already linked")))
                .switchIfEmpty(Mono.defer(() -> {
                    Subscription s = new Subscription();
                    s.setMasterId(masterId);
                    s.setChildId(childId);
                    s.setScalingFactor(scalingFactor != null ? scalingFactor : 1.0);
                    s.setCopyingStatus("ACTIVE");
                    s.setCreatedAt(Instant.now());
                    return subs.save(s).thenReturn(Map.of("message", "Child linked successfully"));
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
