package com.copytrading.broker;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BrokerAccountRepository extends ReactiveCrudRepository<BrokerAccount, UUID> {

    Flux<BrokerAccount> findByUserId(UUID userId);

    Flux<BrokerAccount> findByBrokerId(String brokerId);

    Flux<BrokerAccount> findByUserIdAndBrokerId(UUID userId, String brokerId);

    @Query("SELECT COALESCE(MAX(ip_slot), -1) FROM broker_accounts WHERE broker_id = 'GROWW'")
    Mono<Integer> findMaxGrowwIpSlot();

    Mono<Void> deleteByUserId(UUID userId);
}
