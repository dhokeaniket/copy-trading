package com.copytrading.broker;

import java.util.List;

public class Positions {
  private List<Position> items;

  public List<Position> getItems() {
    return items;
  }
  public void setItems(List<Position> items) {
    this.items = items;
  }

  public static class Position {
    private String symbol;
    private int quantity;
    private String side;
    private String avgPrice;

    public String getSymbol() {
      return symbol;
    }
    public void setSymbol(String symbol) {
      this.symbol = symbol;
    }
    public int getQuantity() {
      return quantity;
    }
    public void setQuantity(int quantity) {
      this.quantity = quantity;
    }
    public String getSide() {
      return side;
    }
    public void setSide(String side) {
      this.side = side;
    }
    public String getAvgPrice() {
      return avgPrice;
    }
    public void setAvgPrice(String avgPrice) {
      this.avgPrice = avgPrice;
    }
  }
}
