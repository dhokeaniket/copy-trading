package com.copytrading.broker;

public class OrderRequest {
  private String symbol;
  private String side;
  private int quantity;
  private String orderType;
  private String price;

  public String getSymbol() {
    return symbol;
  }
  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }
  public String getSide() {
    return side;
  }
  public void setSide(String side) {
    this.side = side;
  }
  public int getQuantity() {
    return quantity;
  }
  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }
  public String getOrderType() {
    return orderType;
  }
  public void setOrderType(String orderType) {
    this.orderType = orderType;
  }
  public String getPrice() {
    return price;
  }
  public void setPrice(String price) {
    this.price = price;
  }
}
