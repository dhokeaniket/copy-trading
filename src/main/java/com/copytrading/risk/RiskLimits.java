package com.copytrading.risk;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("risk_limits")
public class RiskLimits {
  @Id
  private Long childId;
  private int maxTradesPerDay;
  private int maxOpenPositions;
  private String maxExposure;

  public Long getChildId() {
    return childId;
  }
  public void setChildId(Long childId) {
    this.childId = childId;
  }
  public int getMaxTradesPerDay() {
    return maxTradesPerDay;
  }
  public void setMaxTradesPerDay(int maxTradesPerDay) {
    this.maxTradesPerDay = maxTradesPerDay;
  }
  public int getMaxOpenPositions() {
    return maxOpenPositions;
  }
  public void setMaxOpenPositions(int maxOpenPositions) {
    this.maxOpenPositions = maxOpenPositions;
  }
  public String getMaxExposure() {
    return maxExposure;
  }
  public void setMaxExposure(String maxExposure) {
    this.maxExposure = maxExposure;
  }
}
