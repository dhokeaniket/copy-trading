package com.copytrading.trade;

import com.copytrading.replication.TradeEvent;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/trades")
public class TradeController {
  private final KafkaTemplate<String, TradeEvent> kafka;

  public TradeController(KafkaTemplate<String, TradeEvent> kafka) {
    this.kafka = kafka;
  }

  @PostMapping(value = "/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<Void> publish(@RequestBody TradeEvent event) {
    return Mono.fromRunnable(() -> kafka.send("trade-events", String.valueOf(event.getMasterId()), event));
  }
}

