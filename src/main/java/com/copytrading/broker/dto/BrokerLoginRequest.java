package com.copytrading.broker.dto;

public class BrokerLoginRequest {
    private String totpCode;
    private String requestToken;  // For Zerodha (request_token from OAuth redirect)
    private String authCode;      // For Fyers/Upstox (auth_code from OAuth redirect)

    public String getTotpCode() { return totpCode; }
    public void setTotpCode(String totpCode) { this.totpCode = totpCode; }
    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String requestToken) { this.requestToken = requestToken; }
    public String getAuthCode() { return authCode; }
    public void setAuthCode(String authCode) { this.authCode = authCode; }
}
