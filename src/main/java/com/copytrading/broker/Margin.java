package com.copytrading.broker;

public class Margin {
  private String available;
  private String used;
  private String exposure;

  public String getAvailable() {
    return available;
  }
  public void setAvailable(String available) {
    this.available = available;
  }
  public String getUsed() {
    return used;
  }
  public void setUsed(String used) {
    this.used = used;
  }
  public String getExposure() {
    return exposure;
  }
  public void setExposure(String exposure) {
    this.exposure = exposure;
  }
}
