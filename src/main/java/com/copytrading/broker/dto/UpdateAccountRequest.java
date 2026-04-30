package com.copytrading.broker.dto;

public class UpdateAccountRequest {
    private String apiKey;
    private String apiSecret;
    private String accountNickname;
    private String clientId;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getAccountNickname() { return accountNickname; }
    public void setAccountNickname(String accountNickname) { this.accountNickname = accountNickname; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
}
