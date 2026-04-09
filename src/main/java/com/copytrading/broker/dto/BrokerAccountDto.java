package com.copytrading.broker.dto;

import com.copytrading.broker.BrokerAccount;

import java.time.Instant;
import java.util.UUID;

public class BrokerAccountDto {
    private UUID accountId;
    private String brokerId;
    private String brokerName;
    private String clientId;
    private String nickname;
    private String status;
    private boolean sessionActive;
    private Instant linkedAt;
    private Instant lastSyncedAt;
    private double margin;
    private double pnl;
    private int positions;
    private int orders;

    public static BrokerAccountDto from(BrokerAccount a) {
        BrokerAccountDto d = new BrokerAccountDto();
        d.accountId = a.getId();
        d.brokerId = a.getBrokerId();
        d.brokerName = brokerDisplayName(a.getBrokerId());
        d.clientId = a.getClientId();
        d.nickname = a.getNickname();
        d.status = a.getStatus();
        d.sessionActive = a.isSessionActive();
        d.linkedAt = a.getLinkedAt();
        d.lastSyncedAt = a.getSessionExpires() != null ? Instant.now() : null;
        d.margin = 0;
        d.pnl = 0;
        d.positions = 0;
        d.orders = 0;
        return d;
    }

    private static String brokerDisplayName(String id) {
        return switch (id) {
            case "GROWW" -> "Groww";
            case "ZERODHA" -> "Zerodha";
            case "FYERS" -> "Fyers";
            case "UPSTOX" -> "Upstox";
            case "DHAN" -> "Dhan";
            case "ANGELONE" -> "Angel One";
            default -> id;
        };
    }

    public UUID getAccountId() { return accountId; }
    public String getBrokerId() { return brokerId; }
    public String getBrokerName() { return brokerName; }
    public String getClientId() { return clientId; }
    public String getNickname() { return nickname; }
    public String getStatus() { return status; }
    public boolean isSessionActive() { return sessionActive; }
    public Instant getLinkedAt() { return linkedAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public double getMargin() { return margin; }
    public void setMargin(double margin) { this.margin = margin; }
    public double getPnl() { return pnl; }
    public void setPnl(double pnl) { this.pnl = pnl; }
    public int getPositions() { return positions; }
    public void setPositions(int positions) { this.positions = positions; }
    public int getOrders() { return orders; }
    public void setOrders(int orders) { this.orders = orders; }
}
