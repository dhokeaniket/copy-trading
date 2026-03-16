package com.copytrading.broker;

import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.Map;

@Component
public class BrokerRegistry {
  private final Map<BrokerType, BrokerAdapter> adapters = new EnumMap<>(BrokerType.class);

  public void register(BrokerType type, BrokerAdapter adapter) {
    adapters.put(type, adapter);
  }

  public BrokerAdapter get(BrokerType type) {
    return adapters.get(type);
  }
}
