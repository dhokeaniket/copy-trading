package com.copytrading.risk;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("risk_limits")
public class RiskLimits {

    @Id
    @Column("child_id")
    private UUID childId;

    private int maxTradesPerDay;
    private int maxOpenPositions;
    private String maxExposure;

    public UUID getChildId() { return childId; }
    public void setChildId(UUID childId) { this.childId = childId; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int maxTradesPerDay) { this.maxTradesPerDay = maxTradesPerDay; }
    public int getMaxOpenPositions() { return maxOpenPositions; }
    public void setMaxOpenPositions(int maxOpenPositions) { this.maxOpenPositions = maxOpenPositions; }
    public String getMaxExposure() { return maxExposure; }
    public void setMaxExposure(String maxExposure) { this.maxExposure = maxExposure; }
}
