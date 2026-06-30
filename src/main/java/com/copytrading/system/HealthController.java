package com.copytrading.system;

import com.copytrading.engine.CopyEngineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {
  
  private final CopyEngineService copyEngineService;

  public HealthController(CopyEngineService copyEngineService) {
      this.copyEngineService = copyEngineService;
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
        "killSwitchActive", copyEngineService.isGlobalKillSwitchActive(),
        "time", Instant.now().toString()
    ));
  }
}

