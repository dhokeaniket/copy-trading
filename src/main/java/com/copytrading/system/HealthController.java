package com.copytrading.system;

import com.copytrading.config.KillSwitchCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {
  
  private final KillSwitchCache killSwitchCache;

  public HealthController(KillSwitchCache killSwitchCache) {
      this.killSwitchCache = killSwitchCache;
  }

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
        "killSwitchActive", killSwitchCache.isEnabled(),
        "time", Instant.now().toString()
    ));
  }
}

