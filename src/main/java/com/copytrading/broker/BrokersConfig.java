package com.copytrading.broker;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class BrokersConfig {
  private final BrokerRegistry registry;
  private final ZerodhaAdapter zerodhaAdapter;
  private final MockGrowwAdapter mockGrowwAdapter;

  public BrokersConfig(BrokerRegistry registry, ZerodhaAdapter zerodhaAdapter, MockGrowwAdapter mockGrowwAdapter) {
    this.registry = registry;
    this.zerodhaAdapter = zerodhaAdapter;
    this.mockGrowwAdapter = mockGrowwAdapter;
  }

  @PostConstruct
  public void register() {
    registry.register(BrokerType.ZERODHA, zerodhaAdapter);
    registry.register(BrokerType.GROWW, mockGrowwAdapter);
    // Fyers, Upstox are handled directly in BrokerAccountService via API clients
  }
}
