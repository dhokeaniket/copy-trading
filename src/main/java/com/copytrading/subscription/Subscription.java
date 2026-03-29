package com.copytrading.subscription;

import com.copytrading.broker.BrokerType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("subscriptions")
public class Subscription {

    @Id
    private Long id;

    @Column("master_id")
    private UUID masterId;

    @Column("child_id")
    private UUID childId;

    @Column("broker")
    private BrokerType broker;

    @Column("scale")
    private double scale;

    @Column("active")
    private boolean active;

    @Column("created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getChildId() { return childId; }
    public void setChildId(UUID childId) { this.childId = childId; }
    public BrokerType getBroker() { return broker; }
    public void setBroker(BrokerType broker) { this.broker = broker; }
    public double getScale() { return scale; }
    public void setScale(double scale) { this.scale = scale; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
