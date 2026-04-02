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

    public static BrokerAccountDto from(BrokerAccount a) {
        BrokerAccountDto d = new BrokerAccountDto();
        d.accountId = a.getId();
        d.brokerId = a.getBrokerId();
        d.brokerName = a.getBrokerId();
        d.clientId = a.getClientId();
        d.nickname = a.getNickname();
        d.status = a.getStatus();
        d.sessionActive = a.isSessionActive();
        d.linkedAt = a.getLinkedAt();
        return d;
    }

    public UUID getAccountId() { return accountId; }
    public String getBrokerId() { return brokerId; }
    public String getBrokerName() { return brokerName; }
    public String getClientId() { return clientId; }
    public String getNickname() { return nickname; }
    public String getStatus() { return status; }
    public boolean isSessionActive() { return sessionActive; }
    public Instant getLinkedAt() { return linkedAt; }
}
