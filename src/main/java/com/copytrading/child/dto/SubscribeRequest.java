package com.copytrading.child.dto;

import java.util.UUID;

public class SubscribeRequest {
    private UUID masterId;
    private UUID brokerAccountId;
    private Double scalingFactor;
    private String copySides;
    private Boolean allowShortSelling;

    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(UUID brokerAccountId) { this.brokerAccountId = brokerAccountId; }
    public Double getScalingFactor() { return scalingFactor; }
    public void setScalingFactor(Double scalingFactor) { this.scalingFactor = scalingFactor; }
    public String getCopySides() { return copySides; }
    public void setCopySides(String copySides) { this.copySides = copySides; }
    public Boolean getAllowShortSelling() { return allowShortSelling; }
    public void setAllowShortSelling(Boolean allowShortSelling) { this.allowShortSelling = allowShortSelling; }
}
