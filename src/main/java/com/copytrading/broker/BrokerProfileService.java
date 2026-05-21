package com.copytrading.broker;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Spec §3 — normalized broker account profile with 60s cache. */
@Service
public class BrokerProfileService {

    private static final long CACHE_TTL_MS = 60_000;

    private final BrokerAccountRepository repo;
    private final BrokerAccountService brokerService;
    private final ConcurrentHashMap<UUID, CachedProfile> cache = new ConcurrentHashMap<>();

    public BrokerProfileService(BrokerAccountRepository repo, BrokerAccountService brokerService) {
        this.repo = repo;
        this.brokerService = brokerService;
    }

    public Mono<Map<String, Object>> getProfile(UUID accountId, UUID userId, boolean forceRefresh) {
        if (!forceRefresh) {
            CachedProfile cached = cache.get(accountId);
            if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
                return Mono.just(cached.profile);
            }
        }
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> brokerService.getBrokerProfile(accountId, userId)
                        .zipWith(brokerService.getMargin(accountId, userId)
                                .onErrorReturn(Map.of("availableMargin", 0, "usedMargin", 0, "totalFunds", 0)))
                        .zipWith(brokerService.getPositions(accountId, userId)
                                .map(p -> p.getOrDefault("positions", List.of()))
                                .onErrorReturn(List.of()))
                        .map(t -> normalize(a, t.getT1().getT1(), t.getT1().getT2(), (List<?>) t.getT2()))
                        .doOnNext(profile -> cache.put(accountId,
                                new CachedProfile(profile, Instant.now().plusMillis(CACHE_TTL_MS)))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(BrokerAccount a, Map<String, Object> rawProfile,
                                          Map<String, Object> margin, List<?> positions) {
        double available = toDouble(margin.get("availableMargin"));
        double used = toDouble(margin.get("usedMargin"));
        double total = toDouble(margin.get("totalFunds"));
        if (total <= 0) total = available + used;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("accountId", a.getId().toString());
        p.put("broker", a.getBrokerId());
        p.put("clientId", a.getClientId() != null ? a.getClientId() : rawProfile.getOrDefault("clientId", ""));
        p.put("fullName", rawProfile.getOrDefault("name", ""));
        p.put("email", rawProfile.getOrDefault("email", ""));
        p.put("mobile", rawProfile.getOrDefault("mobile", ""));
        p.put("pan", maskPan(String.valueOf(rawProfile.getOrDefault("pan", ""))));
        p.put("segments", rawProfile.getOrDefault("segments", List.of("EQUITY")));
        p.put("exchanges", rawProfile.getOrDefault("exchanges", List.of("NSE")));
        p.put("marginAvailable", available);
        p.put("marginUsed", used);
        p.put("totalMargin", total);
        p.put("marginUsedPercent", total > 0 ? round1(used / total * 100) : 0);
        p.put("marginAvailablePercent", total > 0 ? round1(available / total * 100) : 0);
        p.put("fundsUtilizationStatus", utilizationStatus(used, total));
        p.put("cashBalance", available);
        p.put("collateral", toDouble(margin.get("collateral")));
        p.put("openPositionsCount", positions.size());
        p.put("sessionActive", a.isSessionActive());
        p.put("lastLoginAt", a.getLinkedAt());
        p.put("tokenExpiresAt", a.getSessionExpires());
        p.put("tokenExpiresInHours", hoursUntil(a.getSessionExpires()));
        p.put("isTokenExpired", a.getSessionExpires() != null && a.getSessionExpires().isBefore(Instant.now()));
        p.put("lastSyncedAt", Instant.now().toString());
        p.put("brokerSpecific", rawProfile);
        return p;
    }

    private static String utilizationStatus(double used, double total) {
        if (total <= 0) return "GREEN";
        double pct = used / total * 100;
        if (pct > 80) return "RED";
        if (pct > 60) return "YELLOW";
        return "GREEN";
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 6) return pan;
        return pan.substring(0, 5) + "****" + pan.substring(pan.length() - 1);
    }

    private static double hoursUntil(Instant expires) {
        if (expires == null) return 0;
        return Math.max(0, (expires.toEpochMilli() - Instant.now().toEpochMilli()) / 3600000.0);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private record CachedProfile(Map<String, Object> profile, Instant expiresAt) {}
}
