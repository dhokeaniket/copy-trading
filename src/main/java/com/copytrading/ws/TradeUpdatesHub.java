package com.copytrading.ws;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class TradeUpdatesHub {
  private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

  public void publish(String payload) {
    sink.tryEmitNext(payload);
  }

  public Flux<String> stream() {
    return sink.asFlux();
  }
}

