package com.copytrading.master;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("master_active_accounts")
public class MasterActiveAccount {

    @Id
    @Column("master_id")
    private UUID masterId;

    @Column("broker_account_id")
    private UUID brokerAccountId;

    @Column("activated_at")
    private Instant activatedAt;

    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(UUID brokerAccountId) { this.brokerAccountId = brokerAccountId; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
}
