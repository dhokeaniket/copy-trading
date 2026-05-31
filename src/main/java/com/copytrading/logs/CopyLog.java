package com.copytrading.logs;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("copy_logs")
public class CopyLog {

    @Id
    private Long id;

    @Column("master_id")
    private UUID masterId;

    @Column("child_id")
    private UUID childId;

    @Column("master_trade_id")
    private String masterTradeId;

    private String symbol;
    private Integer qty;

    @Column("child_qty")
    private Integer childQty;

    @Column("child_broker_order_id")
    private String childBrokerOrderId;

    @Column("trade_type")
    private String tradeType;

    @Column("master_status")
    private String masterStatus;

    @Column("child_status")
    private String childStatus;

    @Column("error_message")
    private String errorMessage;

    @Column("skip_reason")
    private String skipReason;

    @Column("latency_ms")
    private Long latencyMs;

    @Column("copy_group_id")
    private String copyGroupId;

    @Column("master_placed_at")
    private Instant masterPlacedAt;

    @Column("engine_received_at")
    private Instant engineReceivedAt;

    @Column("child_placed_at")
    private Instant childPlacedAt;

    @Column("created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getChildId() { return childId; }
    public void setChildId(UUID childId) { this.childId = childId; }
    public String getMasterTradeId() { return masterTradeId; }
    public void setMasterTradeId(String masterTradeId) { this.masterTradeId = masterTradeId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }
    public Integer getChildQty() { return childQty; }
    public void setChildQty(Integer childQty) { this.childQty = childQty; }
    public String getChildBrokerOrderId() { return childBrokerOrderId; }
    public void setChildBrokerOrderId(String childBrokerOrderId) { this.childBrokerOrderId = childBrokerOrderId; }
    public String getTradeType() { return tradeType; }
    public void setTradeType(String tradeType) { this.tradeType = tradeType; }
    public String getMasterStatus() { return masterStatus; }
    public void setMasterStatus(String masterStatus) { this.masterStatus = masterStatus; }
    public String getChildStatus() { return childStatus; }
    public void setChildStatus(String childStatus) { this.childStatus = childStatus; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getCopyGroupId() { return copyGroupId; }
    public void setCopyGroupId(String copyGroupId) { this.copyGroupId = copyGroupId; }
    public Instant getMasterPlacedAt() { return masterPlacedAt; }
    public void setMasterPlacedAt(Instant masterPlacedAt) { this.masterPlacedAt = masterPlacedAt; }
    public Instant getEngineReceivedAt() { return engineReceivedAt; }
    public void setEngineReceivedAt(Instant engineReceivedAt) { this.engineReceivedAt = engineReceivedAt; }
    public Instant getChildPlacedAt() { return childPlacedAt; }
    public void setChildPlacedAt(Instant childPlacedAt) { this.childPlacedAt = childPlacedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
