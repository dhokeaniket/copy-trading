package com.copytrading.subscription;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
  public Flux<Subscription> listByMaster(@PathVariable Long masterId) {
    return subscriptions.listByMaster(masterId);
  }
}

