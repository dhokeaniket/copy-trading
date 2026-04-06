package com.copytrading.subscription;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SubscriptionRepository extends ReactiveCrudRepository<Subscription, Long> {

    Flux<Subscription> findByMasterId(UUID masterId);

    Flux<Subscription> findByChildId(UUID childId);

    Mono<Subscription> findByMasterIdAndChildId(UUID masterId, UUID childId);

    Flux<Subscription> findByMasterIdAndCopyingStatus(UUID masterId, String copyingStatus);

    Flux<Subscription> findByChildIdAndCopyingStatusNot(UUID childId, String status);

    @Modifying
    @Query("UPDATE subscriptions SET copying_status = :status WHERE master_id = :masterId AND child_id = :childId")
    Mono<Integer> updateCopyingStatus(UUID masterId, UUID childId, String status);

    @Modifying
    @Query("UPDATE subscriptions SET copying_status = :status, approved_once = :approvedOnce WHERE master_id = :masterId AND child_id = :childId")
    Mono<Integer> updateStatusAndApproval(UUID masterId, UUID childId, String status, boolean approvedOnce);
}
