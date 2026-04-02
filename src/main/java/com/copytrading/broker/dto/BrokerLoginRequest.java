package com.copytrading.broker.dto;

public class BrokerLoginRequest {
    private String totpCode;

    public String getTotpCode() { return totpCode; }
    public void setTotpCode(String totpCode) { this.totpCode = totpCode; }
}
