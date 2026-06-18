package com.copytrading.master;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MasterActiveAccountRepository extends ReactiveCrudRepository<MasterActiveAccount, UUID> {
    Mono<Void> deleteByMasterId(UUID masterId);
    reactor.core.publisher.Flux<MasterActiveAccount> findByMasterId(UUID masterId);
}
