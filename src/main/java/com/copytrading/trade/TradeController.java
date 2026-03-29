package com.copytrading.trade;

import com.copytrading.replication.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/trades")
@ConditionalOnBean(KafkaTemplate.class)
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);
    private final KafkaTemplate<String, TradeEvent> kafka;

    public TradeController(KafkaTemplate<String, TradeEvent> kafka) {
        this.kafka = kafka;
    }

    @PostMapping(value = "/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> publish(@RequestBody TradeEvent event) {
        return Mono.fromRunnable(() -> {
            String key = String.valueOf(event.getMasterId());
            log.info("TRADE_PUBLISH master={} type={} symbol={} qty={}",
                    event.getMasterId(), event.getType(),
                    event.getOrder() == null ? null : event.getOrder().getSymbol(),
                    event.getOrder() == null ? null : event.getOrder().getQuantity());
            kafka.send("trade-events", key, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("TRADE_PUBLISH_FAILED master={} error={}", event.getMasterId(), ex.getMessage(), ex);
                    return;
                }
                log.info("TRADE_PUBLISHED master={} partition={} offset={}",
                        event.getMasterId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            });
        });
    }
}
