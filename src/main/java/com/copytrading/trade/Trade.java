package com.copytrading.trade;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("trades")
public class Trade {
    @Id private UUID id;
    @Column("user_id") private UUID userId;
    @Column("broker_account_id") private UUID brokerAccountId;
    @Column("broker_order_id") private String brokerOrderId;
    private String instrument;
    private String exchange;
    private String segment;
    @Column("order_type") private String orderType;
    @Column("transaction_type") private String transactionType;
    private int quantity;
    private double price;
    @Column("trigger_price") private Double triggerPrice;
    private String product;
    private String validity;
    private String status;
    @Column("replications_triggered") private int replicationsTriggered;
    @Column("placed_at") private Instant placedAt;
    @Column("executed_at") private Instant executedAt;
    @Column("cancelled_at") private Instant cancelledAt;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; } public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getBrokerAccountId() { return brokerAccountId; } public void setBrokerAccountId(UUID v) { this.brokerAccountId = v; }
    public String getBrokerOrderId() { return brokerOrderId; } public void setBrokerOrderId(String v) { this.brokerOrderId = v; }
    public String getInstrument() { return instrument; } public void setInstrument(String v) { this.instrument = v; }
    public String getExchange() { return exchange; } public void setExchange(String v) { this.exchange = v; }
    public String getSegment() { return segment; } public void setSegment(String v) { this.segment = v; }
    public String getOrderType() { return orderType; } public void setOrderType(String v) { this.orderType = v; }
    public String getTransactionType() { return transactionType; } public void setTransactionType(String v) { this.transactionType = v; }
    public int getQuantity() { return quantity; } public void setQuantity(int v) { this.quantity = v; }
    public double getPrice() { return price; } public void setPrice(double v) { this.price = v; }
    public Double getTriggerPrice() { return triggerPrice; } public void setTriggerPrice(Double v) { this.triggerPrice = v; }
    public String getProduct() { return product; } public void setProduct(String v) { this.product = v; }
    public String getValidity() { return validity; } public void setValidity(String v) { this.validity = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public int getReplicationsTriggered() { return replicationsTriggered; } public void setReplicationsTriggered(int v) { this.replicationsTriggered = v; }
    public Instant getPlacedAt() { return placedAt; } public void setPlacedAt(Instant v) { this.placedAt = v; }
    public Instant getExecutedAt() { return executedAt; } public void setExecutedAt(Instant v) { this.executedAt = v; }
    public Instant getCancelledAt() { return cancelledAt; } public void setCancelledAt(Instant v) { this.cancelledAt = v; }
}
