package com.copytrading.logs;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TradeLogRepository extends ReactiveCrudRepository<TradeLog, Long> {

    Flux<TradeLog> findByMasterId(UUID masterId);

    Flux<TradeLog> findByChildId(UUID childId);
}
