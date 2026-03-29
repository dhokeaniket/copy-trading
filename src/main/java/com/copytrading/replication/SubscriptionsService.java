package com.copytrading.replication;

import com.copytrading.subscription.SubscriptionService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionsService {

    private final SubscriptionService subscriptions;

    public SubscriptionsService(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    public Mono<List<ChildSubscription>> findSubscribedChildren(UUID masterId) {
        return subscriptions.findSubscribedChildren(masterId);
    }
}
