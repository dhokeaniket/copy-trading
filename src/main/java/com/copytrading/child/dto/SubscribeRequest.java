package com.copytrading.child.dto;

import java.util.UUID;

public class SubscribeRequest {
    private UUID masterId;
    private UUID brokerAccountId;
    private Double scalingFactor;

    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(UUID brokerAccountId) { this.brokerAccountId = brokerAccountId; }
    public Double getScalingFactor() { return scalingFactor; }
    public void setScalingFactor(Double scalingFactor) { this.scalingFactor = scalingFactor; }
}
