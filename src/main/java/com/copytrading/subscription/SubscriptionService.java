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
        subscription.setActive(true);
        subscription.setCreatedAt(Instant.now());
        return repository.save(subscription)
                .doOnSuccess(s -> log.info("SUBSCRIPTION_CREATED id={} master={} child={} broker={} scale={}",
                        s.getId(), s.getMasterId(), s.getChildId(), s.getBroker(), s.getScale()));
    }

    public Flux<Subscription> listByMaster(UUID masterId) {
        return repository.findByMasterIdAndActive(masterId, true);
    }

    public Mono<List<ChildSubscription>> findSubscribedChildren(UUID masterId) {
        return repository.findByMasterIdAndActive(masterId, true)
                .map(s -> {
                    ChildSubscription c = new ChildSubscription();
                    c.setChildId(s.getChildId());
                    c.setBroker(s.getBroker());
                    c.setScale(s.getScale());
                    return c;
                })
                .collectList()
                .doOnSuccess(children -> log.info("SUBSCRIPTIONS_FETCHED master={} count={}", masterId, children.size()));
    }
}
