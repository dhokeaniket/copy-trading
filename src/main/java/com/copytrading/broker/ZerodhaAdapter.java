package com.copytrading.broker;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Component
public class ZerodhaAdapter implements BrokerAdapter {
  @Override
  public Mono<String> login(String apiKey, String apiSecret, String otp) {
    return Mono.just("zerodha-mock-session");
  }

  @Override
  public Mono<String> placeOrder(OrderRequest request) {
    if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()) {
      return Mono.error(new IllegalArgumentException("invalid_order"));
    }
    return Mono.just("ZERODHA-MOCK-" + Instant.now().toEpochMilli());
  }

  @Override
  public Mono<Void> cancelOrder(String orderId) {
    if (orderId == null || orderId.isBlank()) {
      return Mono.error(new IllegalArgumentException("invalid_order_id"));
    }
    return Mono.empty();
  }

  @Override
  public Mono<Positions> getPositions() {
    Positions positions = new Positions();
    positions.setItems(List.of());
    return Mono.just(positions);
  }

  @Override
  public Mono<Margin> getMargin() {
    Margin margin = new Margin();
    margin.setAvailable("1000000");
    margin.setUsed("0");
    margin.setExposure("0");
    return Mono.just(margin);
  }
}
