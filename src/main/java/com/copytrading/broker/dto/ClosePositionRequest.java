package com.copytrading.broker.dto;

public class ClosePositionRequest {
    private String symbol;
    private int qty;
    private String type;    // BUY or SELL
    private String product; // MIS, CNC, NRML

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public String getType() { return type != null ? type : "SELL"; }
    public void setType(String type) { this.type = type; }
    public String getProduct() { return product != null ? product : "MIS"; }
    public void setProduct(String product) { this.product = product; }
}
