package com.copytrading.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class TradeWebSocketHandler implements WebSocketHandler {
  private final TradeUpdatesHub hub;

  public TradeWebSocketHandler(TradeUpdatesHub hub) {
    this.hub = hub;
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    Flux<String> incoming = session.receive().map(m -> m.getPayloadAsText()).doOnNext(hub::publish);
    return session.send(hub.stream().map(session::textMessage)).and(incoming.then());
  }
}
