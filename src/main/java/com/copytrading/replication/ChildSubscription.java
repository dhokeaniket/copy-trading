package com.copytrading.replication;

import com.copytrading.broker.BrokerType;

public class ChildSubscription {
  private Long childId;
  private BrokerType broker;
  private double scale;

  public Long getChildId() {
    return childId;
  }
  public void setChildId(Long childId) {
    this.childId = childId;
  }
  public BrokerType getBroker() {
    return broker;
  }
  public void setBroker(BrokerType broker) {
    this.broker = broker;
  }
  public double getScale() {
    return scale;
  }
  public void setScale(double scale) {
    this.scale = scale;
  }
}
