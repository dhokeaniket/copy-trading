package com.copytrading.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-broker-account order-placement rate limiter for child orders.
 *
 * <p>Each broker enforces its own per-second / per-minute caps (see BROKER-FIELD-MAPPING.md, section 6).
 * Placing copied child orders faster than the cap gets the account throttled or temporarily blocked by the
 * broker. This limiter delays (never drops) a placement until it fits within the broker's window.
 *
 * <p>Limits are keyed per (broker, account) — most brokers enforce per-user, and Zerodha per API key; the
 * conservative per-account window covers both.
 */
@Component
public class BrokerRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(BrokerRateLimiter.class);

    /** Per-second order caps (place/modify/cancel) from BROKER-FIELD-MAPPING.md. */
    private static final Map<String, Integer> PER_SEC = Map.of(
            "ZERODHA", 10, "UPSTOX", 10, "DHAN", 10, "GROWW", 10, "FYERS", 10, "ANGELONE", 10);

    /** Per-minute order caps. */
    private static final Map<String, Integer> PER_MIN = Map.of(
            "ZERODHA", 400, "UPSTOX", 500, "DHAN", 250, "GROWW", 250, "FYERS", 200, "ANGELONE", 500);

    private static final int DEFAULT_PER_SEC = 8;   // conservative fallback for unknown brokers
    private static final int DEFAULT_PER_MIN = 200;
    private static final int MAX_WAIT_MS = 5_000;   // never delay a single order longer than this

    // key -> timestamps (ms) of the last accepted placements within the trailing minute
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Value("${engine.rate-limit.enabled:true}")
    private boolean enabled;

    public Mono<Void> acquire(String brokerId, UUID accountId) {
        if (!enabled || brokerId == null) return Mono.empty();
        String b = brokerId.trim().toUpperCase();
        int perSec = PER_SEC.getOrDefault(b, DEFAULT_PER_SEC);
        int perMin = PER_MIN.getOrDefault(b, DEFAULT_PER_MIN);
        String key = b + ":" + accountId;
        return acquire(key, perSec, perMin, 0);
    }

    private Mono<Void> acquire(String key, int perSec, int perMin, int waitedMs) {
        long now = System.currentTimeMillis();
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        long delayMs;
        synchronized (q) {
            // Drop entries older than 60s.
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000) q.pollFirst();
            long inLastSec = q.stream().filter(t -> now - t < 1_000).count();
            int inLastMin = q.size();
            if (inLastSec < perSec && inLastMin < perMin) {
                q.addLast(now);
                return Mono.empty();
            }
            long secWait = inLastSec >= perSec
                    ? 1_000 - (now - nthFromEnd(q, perSec)) : 0;
            long minWait = inLastMin >= perMin
                    ? 60_000 - (now - q.peekFirst()) : 0;
            delayMs = Math.max(1, Math.max(secWait, minWait));
        }
        if (waitedMs + delayMs > MAX_WAIT_MS) {
            // Give up waiting and let it through rather than stalling the copy indefinitely.
            log.warn("RATE_LIMIT_OVERRIDE key={} waited={}ms — letting order through", key, waitedMs);
            synchronized (q) { q.addLast(System.currentTimeMillis()); }
            return Mono.empty();
        }
        final long d = delayMs;
        return Mono.delay(Duration.ofMillis(d))
                .then(Mono.defer(() -> acquire(key, perSec, perMin, (int) (waitedMs + d))));
    }

    /** Timestamp of the n-th most recent entry (used to compute when the oldest of the last n falls out of the 1s window). */
    private static long nthFromEnd(Deque<Long> q, int n) {
        int idx = q.size() - n;
        int i = 0;
        for (Long t : q) {
            if (i++ == idx) return t;
        }
        return q.peekFirst();
    }
}
