package com.copytrading.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
public class TradeWebSocketHandler implements WebSocketHandler {
  @Override
  public Mono<Void> handle(WebSocketSession session) {
    return session.receive().then();
  }
}
