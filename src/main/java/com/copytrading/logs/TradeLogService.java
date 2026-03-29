package com.copytrading.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TradeLogService {
  private static final Logger logger = LoggerFactory.getLogger(TradeLogService.class);
  private final TradeLogRepository logs;

  public TradeLogService(TradeLogRepository logs) {
    this.logs = logs;
  }

  public Mono<TradeLog> log(TradeLog entry) {
    entry.setId(null);
    return logs.save(entry)
        .doOnSuccess(saved -> logger.info("TRADE_LOG_SAVED id={} masterId={} childId={} status={} message={}",
            saved.getId(), saved.getMasterId(), saved.getChildId(), saved.getStatus(), saved.getMessage()))
        .doOnError(e -> logger.error("TRADE_LOG_SAVE_FAILED masterId={} childId={} error={}",
            entry.getMasterId(), entry.getChildId(), e.getMessage(), e));
  }
}

