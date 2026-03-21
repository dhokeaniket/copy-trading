package com.copytrading.subscription;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SubscriptionRepository extends ReactiveCrudRepository<Subscription, Long> {
  Flux<Subscription> findByMasterIdAndActive(Long masterId, boolean active);
}
