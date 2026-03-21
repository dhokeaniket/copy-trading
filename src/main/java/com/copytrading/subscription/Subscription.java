package com.copytrading.subscription;

import com.copytrading.broker.BrokerType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("subscriptions")
public class Subscription {
  @Id
  private Long id;

  @Column("master_id")
  private Long masterId;

  @Column("child_id")
  private Long childId;

  @Column("broker")
  private BrokerType broker;

  @Column("scale")
  private double scale;

  @Column("active")
  private boolean active;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getMasterId() {
    return masterId;
  }

  public void setMasterId(Long masterId) {
    this.masterId = masterId;
  }

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

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
