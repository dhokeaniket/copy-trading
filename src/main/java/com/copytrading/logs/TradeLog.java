package com.copytrading.logs;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("trade_logs")
public class TradeLog {
  @Id
  private Long id;

  @Column("masterid")
  private Long masterId;

  @Column("childid")
  private Long childId;
  private String type;
  private String status;
  private String message;
  private String broker;
  private String reference;

  public Long getId() {
    return id;
  }
  public void setId(Long id) {
    this.id = id;
  }
  public Long getMasterId() {
    return masterId;
  }
  public void setMasterId(Long masterId) {
    this.masterId = masterId;
  }
  public Long getChildId() {
    return childId;
  }
  public void setChildId(Long childId) {
    this.childId = childId;
  }
  public String getType() {
    return type;
  }
  public void setType(String type) {
    this.type = type;
  }
  public String getStatus() {
    return status;
  }
  public void setStatus(String status) {
    this.status = status;
  }
  public String getMessage() {
    return message;
  }
  public void setMessage(String message) {
    this.message = message;
  }
  public String getBroker() {
    return broker;
  }
  public void setBroker(String broker) {
    this.broker = broker;
  }
  public String getReference() {
    return reference;
  }
  public void setReference(String reference) {
    this.reference = reference;
  }
}
