package com.copytrading.broker;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("broker_accounts")
public class BrokerAccount {

    @Id
    private UUID id;

    @Column("user_id")
    private UUID userId;

    @Column("broker_id")
    private String brokerId;

    @Column("client_id")
    private String clientId;

    @Column("api_key")
    private String apiKey;

    @Column("api_secret")
    private String apiSecret;

    @Column("access_token")
    private String accessToken;

    @Column("nickname")
    private String nickname;

    @Column("status")
    private String status;

    @Column("session_active")
    private boolean sessionActive;

    @Column("session_expires")
    private Instant sessionExpires;

    @Column("proxy_host")
    private String proxyHost;

    @Column("proxy_port")
    private Integer proxyPort;

    @Column("proxy_user")
    private String proxyUser;

    @Column("proxy_pass")
    private String proxyPass;

    @Column("totp_secret")
    private String totpSecret;

    @Column("linked_at")
    private Instant linkedAt;

    @Column("ip_slot")
    private int ipSlot;

    @Column("is_copy_enable")
    private Boolean isCopyEnable;

    @Column("token_expiry")
    private Instant tokenExpiry;

    @Column("last_sync_time")
    private Instant lastSyncTime;

    @Column("last_ping_ms")
    private Long lastPingMs;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getBrokerId() { return brokerId; }
    public void setBrokerId(String brokerId) { this.brokerId = brokerId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isSessionActive() { return sessionActive; }
    public void setSessionActive(boolean sessionActive) { this.sessionActive = sessionActive; }
    public Instant getSessionExpires() { return sessionExpires; }
    public void setSessionExpires(Instant sessionExpires) { this.sessionExpires = sessionExpires; }
    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }
    public int getIpSlot() { return ipSlot; }
    public void setIpSlot(int ipSlot) { this.ipSlot = ipSlot; }
    public Boolean getIsCopyEnable() { return isCopyEnable; }
    public void setIsCopyEnable(Boolean isCopyEnable) { this.isCopyEnable = isCopyEnable; }
    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
    public Integer getProxyPort() { return proxyPort; }
    public void setProxyPort(Integer proxyPort) { this.proxyPort = proxyPort; }
    public String getProxyUser() { return proxyUser; }
    public void setProxyUser(String proxyUser) { this.proxyUser = proxyUser; }
    public String getProxyPass() { return proxyPass; }
    public void setProxyPass(String proxyPass) { this.proxyPass = proxyPass; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public Instant getTokenExpiry() { return tokenExpiry; }
    public void setTokenExpiry(Instant tokenExpiry) { this.tokenExpiry = tokenExpiry; }
    public Instant getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(Instant lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    public Long getLastPingMs() { return lastPingMs; }
    public void setLastPingMs(Long lastPingMs) { this.lastPingMs = lastPingMs; }

    /** Returns true if this account has a per-user proxy configured. */
    public boolean hasProxy() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort != null && proxyPort > 0;
    }
}
