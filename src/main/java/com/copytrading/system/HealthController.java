package com.copytrading.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {
  @GetMapping("/")
  public Mono<Map<String, Object>> root() {
    return Mono.just(Map.of(
        "service", "copy-trading-backend",
        "status", "UP"
    ));
  }

  @GetMapping("/health")
  public Mono<Map<String, Object>> health() {
    return Mono.just(Map.of(
        "status", "UP",
        "time", Instant.now().toString()
    ));
  }
}

