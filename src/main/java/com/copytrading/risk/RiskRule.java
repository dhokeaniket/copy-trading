package com.copytrading.risk;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("risk_rules")
public class RiskRule {
    @Id @Column("user_id") private UUID userId;
    @Column("max_trades_per_day") private int maxTradesPerDay = 50;
    @Column("max_open_positions") private int maxOpenPositions = 20;
    @Column("max_capital_exposure") private double maxCapitalExposure = 80;
    @Column("margin_check_enabled") private boolean marginCheckEnabled = true;
    @Column("updated_at") private Instant updatedAt;

    public UUID getUserId() { return userId; } public void setUserId(UUID v) { this.userId = v; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; } public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }
    public int getMaxOpenPositions() { return maxOpenPositions; } public void setMaxOpenPositions(int v) { this.maxOpenPositions = v; }
    public double getMaxCapitalExposure() { return maxCapitalExposure; } public void setMaxCapitalExposure(double v) { this.maxCapitalExposure = v; }
    public boolean isMarginCheckEnabled() { return marginCheckEnabled; } public void setMarginCheckEnabled(boolean v) { this.marginCheckEnabled = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
