package com.copytrading.positions;

/**
 * Normalized position DTO returned by the positions API.
 * Aggregates data from any broker into a consistent shape.
 */
public class PositionDto {

    private String symbol;
    private int qty;
    private double avgPrice;   // entry price
    private double ltp;        // last traded price
    private double pnl;        // (ltp - avgPrice) * qty
    private String side;       // BUY or SELL
    private String exchange;
    private String product;    // MIS, CNC, NRML etc.

    public PositionDto() {}

    public PositionDto(String symbol, int qty, double avgPrice, double ltp, String side, String exchange, String product) {
        this.symbol = symbol;
        this.qty = qty;
        this.avgPrice = avgPrice;
        this.ltp = ltp;
        this.side = side;
        this.exchange = exchange;
        this.product = product;
        // P&L depends on side: BUY = (ltp - avg) * qty, SELL = (avg - ltp) * qty
        if ("SELL".equalsIgnoreCase(side)) {
            this.pnl = (avgPrice - ltp) * qty;
        } else {
            this.pnl = (ltp - avgPrice) * qty;
        }
    }

    /** Constructor with broker-provided P&L (preferred — avoids recalculation errors). */
    public PositionDto(String symbol, int qty, double avgPrice, double ltp, String side, String exchange, String product, double brokerPnl) {
        this.symbol = symbol;
        this.qty = qty;
        this.avgPrice = avgPrice;
        this.ltp = ltp;
        this.side = side;
        this.exchange = exchange;
        this.product = product;
        this.pnl = brokerPnl;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public double getAvgPrice() { return avgPrice; }
    public void setAvgPrice(double avgPrice) { this.avgPrice = avgPrice; }
    public double getLtp() { return ltp; }
    public void setLtp(double ltp) { this.ltp = ltp; }
    public double getPnl() { return pnl; }
    public void setPnl(double pnl) { this.pnl = pnl; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }

    /** Recalculate PnL based on current avgPrice, ltp, qty */
    public void recalculatePnl() {
        this.pnl = (this.ltp - this.avgPrice) * this.qty;
    }
}
