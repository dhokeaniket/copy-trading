package com.copytrading.replication;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class TradeEventsConsumer {
  private final TradeReplicationService replicationService;
  private final SubscriptionsService subscriptionsService;

  public TradeEventsConsumer(TradeReplicationService replicationService, SubscriptionsService subscriptionsService) {
    this.replicationService = replicationService;
    this.subscriptionsService = subscriptionsService;
  }

  @KafkaListener(topics = "trade-events", groupId = "copy-trading-consumer")
  public void onEvent(TradeEvent event) {
    List<ChildSubscription> children = subscriptionsService.findSubscribedChildren(event.getMasterId());
    Mono<Void> task = replicationService.replicateTrade(event, children);
    task.subscribe();
  }
}
