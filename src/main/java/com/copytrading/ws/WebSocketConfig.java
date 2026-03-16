package com.copytrading.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;

import java.util.Map;

@Configuration
public class WebSocketConfig {
  @Bean
  public HandlerMapping webSocketMapping(TradeWebSocketHandler handler) {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setUrlMap(Map.of("/ws/trades", handler));
    mapping.setOrder(10);
    return mapping;
  }

  @Bean
  public WebSocketService webSocketService() {
    return new HandshakeWebSocketService();
  }
}
