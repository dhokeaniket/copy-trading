package com.copytrading.subscription;

import com.copytrading.replication.ChildSubscription;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class SubscriptionService {
  private final SubscriptionRepository repository;

  public SubscriptionService(SubscriptionRepository repository) {
    this.repository = repository;
  }

  public Mono<Subscription> create(Subscription subscription) {
    subscription.setId(null);
    subscription.setActive(true);
    return repository.save(subscription);
  }

  public Flux<Subscription> listByMaster(Long masterId) {
    return repository.findByMasterIdAndActive(masterId, true);
  }

  public Mono<List<ChildSubscription>> findSubscribedChildren(Long masterId) {
    return repository.findByMasterIdAndActive(masterId, true)
        .map(s -> {
          ChildSubscription c = new ChildSubscription();
          c.setChildId(s.getChildId());
          c.setBroker(s.getBroker());
          c.setScale(s.getScale());
          return c;
        })
        .collectList();
  }
}
