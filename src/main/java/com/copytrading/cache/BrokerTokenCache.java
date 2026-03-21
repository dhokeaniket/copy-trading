package com.copytrading.cache;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class BrokerTokenCache {
  private final ReactiveStringRedisTemplate redis;

  public BrokerTokenCache(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  public Mono<Boolean> putToken(long userId, String broker, String token, Duration ttl) {
    String key = key(userId, broker);
    return redis.opsForValue().set(key, token, ttl);
  }

  public Mono<String> getToken(long userId, String broker) {
    return redis.opsForValue().get(key(userId, broker));
  }

  private String key(long userId, String broker) {
    return "broker:token:" + broker + ":" + userId;
  }
}
