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

    /**
     * Find all broker accounts that should be polled for master copy-trading.
     * An account is pollable if:
     *   - session_active = true (has a valid token)
     *   - is_copy_enable IS NULL or true (user hasn't toggled it OFF)
     *   - The user has at least one ACTIVE subscription as a master
     */
    @Query("SELECT DISTINCT ba.* FROM broker_accounts ba " +
           "INNER JOIN subscriptions s ON s.master_id = ba.user_id AND s.copying_status = 'ACTIVE' " +
           "WHERE ba.session_active = true AND (ba.is_copy_enable IS NULL OR ba.is_copy_enable = true) AND ba.access_token IS NOT NULL")
    Flux<BrokerAccount> findPollableMasterAccounts();
}
