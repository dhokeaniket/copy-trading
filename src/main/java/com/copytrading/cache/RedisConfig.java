package com.copytrading.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
  @Bean
  public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
    StringRedisSerializer serializer = new StringRedisSerializer();
    RedisSerializationContext<String, String> context = RedisSerializationContext.<String, String>newSerializationContext(serializer)
        .value(serializer)
        .build();
    return new ReactiveStringRedisTemplate(factory, context);
  }
}

