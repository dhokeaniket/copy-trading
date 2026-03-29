package com.copytrading.cache;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Service
public class BrokerTokenCache {

    private final ReactiveStringRedisTemplate redis;

    public BrokerTokenCache(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Boolean> putToken(UUID userId, String broker, String token, Duration ttl) {
        return redis.opsForValue().set(key(userId, broker), token, ttl);
    }

    public Mono<String> getToken(UUID userId, String broker) {
        return redis.opsForValue().get(key(userId, broker));
    }

    private String key(UUID userId, String broker) {
        return "broker:token:" + broker + ":" + userId;
    }
}
