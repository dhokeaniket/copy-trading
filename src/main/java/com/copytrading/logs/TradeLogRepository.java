package com.copytrading.logs;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TradeLogRepository extends ReactiveCrudRepository<TradeLog, Long> {
  Flux<TradeLog> findByMasterId(Long masterId);
  Flux<TradeLog> findByChildId(Long childId);
}
