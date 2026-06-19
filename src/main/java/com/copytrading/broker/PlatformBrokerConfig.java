package com.copytrading.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "brokers")
public class PlatformBrokerConfig {

    private BrokerCreds groww = new BrokerCreds();
    private BrokerCreds zerodha = new BrokerCreds();
    private BrokerCreds dhan = new BrokerCreds();
    private String callbackUrl;
    /** Outbound IP to whitelist in Groww (and similar) broker dashboards. */
    private String serverEgressIp;

    public BrokerCreds getGroww() { return groww; }
    public void setGroww(BrokerCreds groww) { this.groww = groww; }
    public BrokerCreds getZerodha() { return zerodha; }
    public void setZerodha(BrokerCreds zerodha) { this.zerodha = zerodha; }
    public BrokerCreds getDhan() { return dhan; }
    public void setDhan(BrokerCreds dhan) { this.dhan = dhan; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getServerEgressIp() { return serverEgressIp; }
    public void setServerEgressIp(String serverEgressIp) { this.serverEgressIp = serverEgressIp; }

    public BrokerCreds getFor(String brokerId) {
        return switch (brokerId) {
            case "GROWW" -> groww;
            case "ZERODHA" -> zerodha;
            case "DHAN" -> dhan;
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
