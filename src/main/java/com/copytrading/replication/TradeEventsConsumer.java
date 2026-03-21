package com.copytrading.replication;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class TradeEventsConsumer {
  private final TradeReplicationService replicationService;
  private final SubscriptionsService subscriptionsService;

  public TradeEventsConsumer(TradeReplicationService replicationService, SubscriptionsService subscriptionsService) {
    this.replicationService = replicationService;
    this.subscriptionsService = subscriptionsService;
  }

  @KafkaListener(topics = "trade-events", groupId = "copy-trading-consumer", autoStartup = "${app.kafka.enabled:false}")
  public void onEvent(TradeEvent event) {
    Mono<Void> task = subscriptionsService.findSubscribedChildren(event.getMasterId())
        .flatMap(children -> replicationService.replicateTrade(event, children));
    task.subscribe();
  }
}
