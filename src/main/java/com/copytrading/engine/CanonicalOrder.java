package com.copytrading.engine;

/**
 * Broker-agnostic order snapshot used across polling, postbacks, and copy engine.
 */
public class CanonicalOrder {

    private String orderId;
    private String sourceBrokerId;
    private String symbol;
    private String side;
    private int filledQty;
    private String product;
    private String orderType;
    private double price;
    private double triggerPrice;
    private String exchange;
    private String segment;
    private InstrumentType instrumentType;
    private String status;
    private boolean readyForCopy;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getSourceBrokerId() { return sourceBrokerId; }
    public void setSourceBrokerId(String sourceBrokerId) { this.sourceBrokerId = sourceBrokerId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public int getFilledQty() { return filledQty; }
    public void setFilledQty(int filledQty) { this.filledQty = filledQty; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public double getTriggerPrice() { return triggerPrice; }
    public void setTriggerPrice(double triggerPrice) { this.triggerPrice = triggerPrice; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }
    public InstrumentType getInstrumentType() { return instrumentType; }
    public void setInstrumentType(InstrumentType instrumentType) { this.instrumentType = instrumentType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isReadyForCopy() { return readyForCopy; }
    public void setReadyForCopy(boolean readyForCopy) { this.readyForCopy = readyForCopy; }
}
