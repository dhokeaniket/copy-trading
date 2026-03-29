package com.copytrading.replication;

import com.copytrading.broker.BrokerType;

import java.util.UUID;

public class ChildSubscription {

    private UUID childId;
    private BrokerType broker;
    private double scale;

    public UUID getChildId() { return childId; }
    public void setChildId(UUID childId) { this.childId = childId; }
    public BrokerType getBroker() { return broker; }
    public void setBroker(BrokerType broker) { this.broker = broker; }
    public double getScale() { return scale; }
    public void setScale(double scale) { this.scale = scale; }
}
