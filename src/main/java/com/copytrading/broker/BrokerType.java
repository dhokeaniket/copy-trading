package com.copytrading.broker;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum BrokerType {
  ZERODHA,
  GROWW,
  UPSTOX,
  ANGEL_ONE,
  DHAN;

  @JsonCreator
  public static BrokerType fromValue(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toUpperCase().replace('-', '_');
    if ("GROW".equals(normalized) || "GROWW".equals(normalized)) {
      return GROWW;
    }
    return BrokerType.valueOf(normalized);
  }
}
