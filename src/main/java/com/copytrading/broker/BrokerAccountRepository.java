package com.copytrading.broker;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface BrokerAccountRepository extends ReactiveCrudRepository<BrokerAccount, UUID> {

    Flux<BrokerAccount> findByUserId(UUID userId);

    Flux<BrokerAccount> findByBrokerId(String brokerId);

    Flux<BrokerAccount> findByUserIdAndBrokerId(UUID userId, String brokerId);
}
