package com.copytrading.child.dto;

import java.util.UUID;

public class SubscribeRequest {
    private UUID masterId;
    private UUID brokerAccountId;

    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(UUID brokerAccountId) { this.brokerAccountId = brokerAccountId; }
}
