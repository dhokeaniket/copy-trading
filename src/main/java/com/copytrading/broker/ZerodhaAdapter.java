package com.copytrading.broker;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ZerodhaAdapter implements BrokerAdapter {
  @Override
  public Mono<String> login(String apiKey, String apiSecret, String otp) {
    return Mono.error(new UnsupportedOperationException("not_implemented"));
  }

  @Override
  public Mono<String> placeOrder(OrderRequest request) {
    return Mono.error(new UnsupportedOperationException("not_implemented"));
  }

  @Override
  public Mono<Void> cancelOrder(String orderId) {
    return Mono.error(new UnsupportedOperationException("not_implemented"));
  }

  @Override
  public Mono<Positions> getPositions() {
    return Mono.error(new UnsupportedOperationException("not_implemented"));
  }

  @Override
  public Mono<Margin> getMargin() {
    return Mono.error(new UnsupportedOperationException("not_implemented"));
  }
}
