package com.copytrading.engine;

public class CopyTradeRequest {
    private String symbol;
    private int qty;
    private String side;        // BUY or SELL
    private String product;     // MIS, CNC, NRML (default MIS)
    private String orderType;   // MARKET, LIMIT (default MARKET)
    private double price;       // 0 for MARKET orders
    private String exchange;    // NSE, BSE (default NSE)

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
}
