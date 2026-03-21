package com.copytrading.logs;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TradeLogService {
  private final TradeLogRepository logs;

  public TradeLogService(TradeLogRepository logs) {
    this.logs = logs;
  }

  public Mono<TradeLog> log(TradeLog log) {
    log.setId(null);
    return logs.save(log);
  }
}

