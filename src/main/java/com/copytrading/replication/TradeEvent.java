package com.copytrading.replication;

import com.copytrading.broker.OrderRequest;

public class TradeEvent {
  private Long masterId;
  private String type;
  private OrderRequest order;
  private String orderId;

  public Long getMasterId() {
    return masterId;
  }
  public void setMasterId(Long masterId) {
    this.masterId = masterId;
  }
  public String getType() {
    return type;
  }
  public void setType(String type) {
    this.type = type;
  }
  public OrderRequest getOrder() {
    return order;
  }
  public void setOrder(OrderRequest order) {
    this.order = order;
  }
  public String getOrderId() {
    return orderId;
  }
  public void setOrderId(String orderId) {
    this.orderId = orderId;
  }
}
