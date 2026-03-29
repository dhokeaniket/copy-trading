package com.copytrading.broker;

import reactor.core.publisher.Mono;

public interface BrokerAdapter {
  Mono<String> login(String apiKey, String apiSecret, String otp);
  Mono<String> placeOrder(OrderRequest request);
  Mono<Void> cancelOrder(String orderId);
  Mono<Positions> getPositions();
  Mono<Margin> getMargin();
}
