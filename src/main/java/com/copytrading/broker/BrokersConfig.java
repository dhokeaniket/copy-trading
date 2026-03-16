package com.copytrading.broker;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class BrokersConfig {
  private final BrokerRegistry registry;
  private final ZerodhaAdapter zerodhaAdapter;

  public BrokersConfig(BrokerRegistry registry, ZerodhaAdapter zerodhaAdapter) {
    this.registry = registry;
    this.zerodhaAdapter = zerodhaAdapter;
  }

  @PostConstruct
  public void register() {
    registry.register(BrokerType.ZERODHA, zerodhaAdapter);
  }
}
