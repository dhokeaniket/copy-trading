package com.copytrading.broker;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Component
public class MockGrowwAdapter implements BrokerAdapter {
  @Override
  public Mono<String> login(String apiKey, String apiSecret, String otp) {
    return Mono.just("mock-groww-login-ok");
  }

  @Override
  public Mono<String> placeOrder(OrderRequest request) {
    return Mono.just("GROWW-MOCK-" + Instant.now().toEpochMilli());
  }

  @Override
  public Mono<Void> cancelOrder(String orderId) {
    return Mono.empty();
  }

  @Override
  public Mono<Positions> getPositions() {
    return Mono.just(new Positions());
  }

  @Override
  public Mono<Margin> getMargin() {
    Margin margin = new Margin();
    margin.setAvailable("1000000");
    return Mono.just(margin);
  }
}
