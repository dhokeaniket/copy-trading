package com.copytrading.engine;

import com.copytrading.subscription.Subscription;
import com.copytrading.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of ACTIVE subscriptions per master.
 * Avoids DB hit on every copy-trade cycle (polling runs every 500ms).
 * Auto-refreshes every 30s and invalidates on subscription changes.
 */
@Component
public class SubscriptionCache {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCache.class);
    private static final long TTL_MS = 30_000; // 30 seconds

    private final SubscriptionRepository repo;

    // masterId → (list of active subs, loaded timestamp)
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public SubscriptionCache(SubscriptionRepository repo) {
        this.repo = repo;
    }

    public Mono<List<Subscription>> getActiveSubscriptions(UUID masterId) {
        CacheEntry entry = cache.get(masterId);
        if (entry != null && !entry.isExpired()) {
            return Mono.just(entry.subscriptions);
        }
        return repo.findByMasterId(masterId)
                .filter(s -> "ACTIVE".equals(s.getCopyingStatus()))
                .collectList()
                .doOnNext(subs -> cache.put(masterId, new CacheEntry(subs)))
                .defaultIfEmpty(List.of());
    }

    /** Call this when a subscription is updated (status change, tolerance change, etc.) */
    public void invalidate(UUID masterId) {
        cache.remove(masterId);
    }

    /** Invalidate all (e.g., on bulk operations) */
    public void invalidateAll() {
        cache.clear();
    }

    private static class CacheEntry {
        final List<Subscription> subscriptions;
        final long loadedAt;

        CacheEntry(List<Subscription> subscriptions) {
            this.subscriptions = subscriptions;
            this.loadedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - loadedAt > TTL_MS;
        }
    }
}
