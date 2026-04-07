package com.copytrading.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "brokers")
public class PlatformBrokerConfig {

    private BrokerCreds zerodha = new BrokerCreds();
    private BrokerCreds fyers = new BrokerCreds();
    private BrokerCreds upstox = new BrokerCreds();
    private String callbackUrl;

    public BrokerCreds getZerodha() { return zerodha; }
    public void setZerodha(BrokerCreds zerodha) { this.zerodha = zerodha; }
    public BrokerCreds getFyers() { return fyers; }
    public void setFyers(BrokerCreds fyers) { this.fyers = fyers; }
    public BrokerCreds getUpstox() { return upstox; }
    public void setUpstox(BrokerCreds upstox) { this.upstox = upstox; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }

    public BrokerCreds getFor(String brokerId) {
        return switch (brokerId) {
            case "ZERODHA" -> zerodha;
            case "FYERS" -> fyers;
            case "UPSTOX" -> upstox;
            default -> null;
        };
    }

    public static class BrokerCreds {
        private String apiKey;
        private String apiSecret;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    }
}
