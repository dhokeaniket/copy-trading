package com.copytrading.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed cache for polling state.
 * Stores known order IDs so they survive app restarts.
 * Key: poll:orders:<masterId>  Value: Set of order IDs
 * TTL: 24 hours (reset daily)
 */
@Service
public class PollingStateCache {

    private static final Logger log = LoggerFactory.getLogger(PollingStateCache.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final ReactiveStringRedisTemplate redis;

    public PollingStateCache(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Check if order ID is already known (returns true if NEW, false if already seen) */
    public Mono<Boolean> isNewOrder(UUID masterId, String orderId) {
        String key = "poll:orders:" + masterId;
        return redis.opsForSet().isMember(key, orderId)
                .map(isMember -> !isMember)
                .onErrorReturn(true); // If Redis fails, treat as new
    }

    /** Mark order ID as known */
    public Mono<Void> markOrderKnown(UUID masterId, String orderId) {
        String key = "poll:orders:" + masterId;
        return redis.opsForSet().add(key, orderId)
                .then(redis.expire(key, TTL))
                .then()
                .onErrorResume(e -> {
                    log.warn("Redis markOrderKnown failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /** Clear all known orders for a master (start of day reset) */
    public Mono<Void> resetMaster(UUID masterId) {
        return redis.delete("poll:orders:" + masterId).then();
    }

    /** Clear all polling state */
    public Mono<Void> resetAll() {
        return redis.keys("poll:orders:*")
                .flatMap(redis::delete)
                .then();
    }

    /** Save polling enabled state to Redis (survives restarts) */
    public Mono<Void> setPollingEnabled(boolean enabled) {
        return redis.opsForValue().set("poll:enabled", String.valueOf(enabled))
                .then()
                .onErrorResume(e -> {
                    log.warn("Redis setPollingEnabled failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /** Get polling enabled state from Redis. Returns null if not set. */
    public Mono<Boolean> getPollingEnabled() {
        return redis.opsForValue().get("poll:enabled")
                .map(Boolean::parseBoolean)
                .onErrorReturn(true); // Default to ON if Redis fails
    }
}
