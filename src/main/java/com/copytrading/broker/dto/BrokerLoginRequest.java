package com.copytrading.broker.dto;

public class BrokerLoginRequest {
    private String totpCode;
    private String requestToken;  // For Zerodha (request_token from OAuth redirect)
    private String authCode;      // For Fyers/Upstox (auth_code from OAuth redirect)
    private String clientId;      // For Dhan (dhanClientId) — optional, saved if provided
    /**
     * Must match the {@code redirect_uri} used when opening the broker OAuth page
     * (same value as in {@code GET .../oauth-url?redirectUri=...}). If omitted, the
     * platform {@code brokers.callbackUrl} is used — mismatch breaks Upstox/Fyers token exchange.
     */
    private String redirectUri;

    public String getTotpCode() { return totpCode; }
    public void setTotpCode(String totpCode) { this.totpCode = totpCode; }
    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String requestToken) { this.requestToken = requestToken; }
    public String getAuthCode() { return authCode; }
    public void setAuthCode(String authCode) { this.authCode = authCode; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
}
