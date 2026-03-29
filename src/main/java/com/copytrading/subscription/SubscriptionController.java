package com.copytrading.subscription;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Subscription> create(@RequestBody Subscription subscription) {
        return subscriptions.create(subscription);
    }

    @GetMapping(value = "/master/{masterId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Subscription> listByMaster(@PathVariable UUID masterId) {
        return subscriptions.listByMaster(masterId);
    }
}
