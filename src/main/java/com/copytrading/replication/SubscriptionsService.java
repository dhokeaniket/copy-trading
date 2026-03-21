package com.copytrading.replication;

import com.copytrading.subscription.SubscriptionService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SubscriptionsService {
  private final SubscriptionService subscriptions;

  public SubscriptionsService(SubscriptionService subscriptions) {
    this.subscriptions = subscriptions;
  }

  public Mono<java.util.List<ChildSubscription>> findSubscribedChildren(Long masterId) {
    return subscriptions.findSubscribedChildren(masterId);
  }
}
