package com.copytrading.risk;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("risk_rules")
public class RiskRule {

    @Id
    @Column("user_id")
    private UUID userId;

    @Column("max_trades_per_day")
    private int maxTradesPerDay;

    @Column("max_open_positions")
    private int maxOpenPositions;

    @Column("max_capital_exposure")
    private double maxCapitalExposure;

    @Column("margin_check_enabled")
    private boolean marginCheckEnabled;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("copy_paused")
    private boolean copyPaused;

    @Column("paused_until")
    private Instant pausedUntil;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int maxTradesPerDay) { this.maxTradesPerDay = maxTradesPerDay; }
    public int getMaxOpenPositions() { return maxOpenPositions; }
    public void setMaxOpenPositions(int maxOpenPositions) { this.maxOpenPositions = maxOpenPositions; }
    public double getMaxCapitalExposure() { return maxCapitalExposure; }
    public void setMaxCapitalExposure(double maxCapitalExposure) { this.maxCapitalExposure = maxCapitalExposure; }
    public boolean isMarginCheckEnabled() { return marginCheckEnabled; }
    public void setMarginCheckEnabled(boolean marginCheckEnabled) { this.marginCheckEnabled = marginCheckEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isCopyPaused() { return copyPaused; }
    public void setCopyPaused(boolean copyPaused) { this.copyPaused = copyPaused; }
    public Instant getPausedUntil() { return pausedUntil; }
    public void setPausedUntil(Instant pausedUntil) { this.pausedUntil = pausedUntil; }
}
