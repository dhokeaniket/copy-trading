package com.copytrading.subscription;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SubscriptionRepository extends ReactiveCrudRepository<Subscription, Long> {

    Flux<Subscription> findByMasterIdAndActive(UUID masterId, boolean active);

    Flux<Subscription> findByChildIdAndActive(UUID childId, boolean active);
}
