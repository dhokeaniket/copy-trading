package com.copytrading.logs;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("broker_error_logs")
public class BrokerErrorLog {
    @Id private UUID id;
    @Column("user_id") private UUID userId;
    @Column("broker_account_id") private UUID brokerAccountId;
    @Column("broker_name") private String brokerName;
    @Column("error_code") private String errorCode;
    @Column("error_message") private String errorMessage;
    @Column("trade_id") private UUID tradeId;
    @Column("created_at") private Instant createdAt;

    public UUID getId() { return id; } public void setId(UUID v) { this.id = v; }
    public UUID getUserId() { return userId; } public void setUserId(UUID v) { this.userId = v; }
    public UUID getBrokerAccountId() { return brokerAccountId; } public void setBrokerAccountId(UUID v) { this.brokerAccountId = v; }
    public String getBrokerName() { return brokerName; } public void setBrokerName(String v) { this.brokerName = v; }
    public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { this.errorCode = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { this.errorMessage = v; }
    public UUID getTradeId() { return tradeId; } public void setTradeId(UUID v) { this.tradeId = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { this.createdAt = v; }
}
