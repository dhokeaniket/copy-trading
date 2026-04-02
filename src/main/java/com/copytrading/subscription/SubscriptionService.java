package com.copytrading.subscription;

import com.copytrading.replication.ChildSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private final SubscriptionRepository repository;

    public SubscriptionService(SubscriptionRepository repository) {
        this.repository = repository;
    }

    public Mono<Subscription> create(Subscription subscription) {
        subscription.setId(null);
        subscription.setCopyingStatus("ACTIVE");
        subscription.setCreatedAt(Instant.now());
        return repository.save(subscription)
                .doOnSuccess(s -> log.info("SUBSCRIPTION_CREATED id={} master={} child={}",
                        s.getId(), s.getMasterId(), s.getChildId()));
    }

    public Flux<Subscription> listByMaster(UUID masterId) {
        return repository.findByMasterIdAndCopyingStatus(masterId, "ACTIVE");
    }

    public Mono<List<ChildSubscription>> findSubscribedChildren(UUID masterId) {
        return repository.findByMasterIdAndCopyingStatus(masterId, "ACTIVE")
                .map(s -> {
                    ChildSubscription c = new ChildSubscription();
                    c.setChildId(s.getChildId());
                    c.setScale(s.getScalingFactor());
                    return c;
                })
                .collectList()
                .doOnSuccess(children -> log.info("SUBSCRIPTIONS_FETCHED master={} count={}", masterId, children.size()));
    }
}
