package com.copytrading.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class TradeEventsConsumer {
  private static final Logger log = LoggerFactory.getLogger(TradeEventsConsumer.class);
  private final TradeReplicationService replicationService;
  private final SubscriptionsService subscriptionsService;

  public TradeEventsConsumer(TradeReplicationService replicationService, SubscriptionsService subscriptionsService) {
    this.replicationService = replicationService;
    this.subscriptionsService = subscriptionsService;
  }

  @KafkaListener(topics = "trade-events", groupId = "copy-trading-consumer", autoStartup = "${app.kafka.enabled:false}")
  public void onEvent(TradeEvent event) {
    log.info("TRADE_CONSUMED masterId={} type={} symbol={}",
        event.getMasterId(),
        event.getType(),
        event.getOrder() == null ? null : event.getOrder().getSymbol());
    Mono<Void> task = subscriptionsService.findSubscribedChildren(event.getMasterId())
        .flatMap(children -> {
          log.info("SUBSCRIPTIONS_FOUND masterId={} childrenCount={}", event.getMasterId(), children.size());
          return replicationService.replicateTrade(event, children);
        })
        .doOnError(e -> log.error("TRADE_CONSUME_PIPELINE_FAILED masterId={} error={}", event.getMasterId(), e.getMessage(), e))
        .doOnSuccess(v -> log.info("TRADE_CONSUME_PIPELINE_DONE masterId={}", event.getMasterId()));
    task.subscribe();
  }
}
