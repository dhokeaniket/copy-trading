package com.copytrading.logs;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface BrokerErrorLogRepository extends ReactiveCrudRepository<BrokerErrorLog, UUID> {
    Flux<BrokerErrorLog> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Flux<BrokerErrorLog> findByBrokerAccountIdOrderByCreatedAtDesc(UUID brokerAccountId);
    Flux<BrokerErrorLog> findAllByOrderByCreatedAtDesc();
}
