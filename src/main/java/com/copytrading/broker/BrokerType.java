package com.copytrading.broker;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum BrokerType {
  ZERODHA,
  GROWW,
  UPSTOX,
  FYERS,
  ANGEL_ONE,
  DHAN;

  @JsonCreator
  public static BrokerType fromValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    if ("GROW".equals(normalized) || "GROWW".equals(normalized)) {
      return GROWW;
    }
    if ("FYER".equals(normalized) || "FYERS".equals(normalized)) {
      return FYERS;
    }
    if ("ANGELONE".equals(normalized) || "ANGEL_ONE".equals(normalized) || "ANGEL ONE".equals(raw.trim().toUpperCase())) {
      return ANGEL_ONE;
    }
    return BrokerType.valueOf(normalized);
  }
}
