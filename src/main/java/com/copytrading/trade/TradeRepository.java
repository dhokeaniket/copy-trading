package com.copytrading.trade;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TradeRepository extends ReactiveCrudRepository<Trade, UUID> {
    Flux<Trade> findByUserIdOrderByPlacedAtDesc(UUID userId);
    Flux<Trade> findByUserIdAndStatusOrderByPlacedAtDesc(UUID userId, String status);
    Flux<Trade> findByUserIdAndStatus(UUID userId, String status);

    @Query("SELECT COUNT(*) FROM trades WHERE user_id = :userId AND placed_at >= CURRENT_DATE")
    Mono<Long> countTodayTrades(UUID userId);
}
