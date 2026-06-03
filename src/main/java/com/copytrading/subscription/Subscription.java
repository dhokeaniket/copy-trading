package com.copytrading.subscription;

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

    @Column("broker_account_id")
    private UUID brokerAccountId;

    @Column("scaling_factor")
    private double scalingFactor;

    @Column("copying_status")
    private String copyingStatus;

    @Column("approved_once")
    private boolean approvedOnce;

    @Column("created_at")
    private Instant createdAt;

    /** BUY_ONLY (default), BUY_AND_SELL, MIRROR — see {@link CopySides}. */
    @Column("copy_sides")
    private String copySides;

    @Column("allow_short_selling")
    private boolean allowShortSelling;

    /** Price tolerance percentage (0.0 to 10.0). Child's LIMIT orders use masterPrice ± tolerance%. Default 2%. */
    @Column("price_tolerance_pct")
    private double priceTolerancePct;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public UUID getChildId() { return childId; }
    public void setChildId(UUID childId) { this.childId = childId; }
    public UUID getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(UUID brokerAccountId) { this.brokerAccountId = brokerAccountId; }
    public double getScalingFactor() { return scalingFactor; }
    public void setScalingFactor(double scalingFactor) { this.scalingFactor = scalingFactor; }
    public String getCopyingStatus() { return copyingStatus; }
    public void setCopyingStatus(String copyingStatus) { this.copyingStatus = copyingStatus; }
    public boolean isApprovedOnce() { return approvedOnce; }
    public void setApprovedOnce(boolean approvedOnce) { this.approvedOnce = approvedOnce; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCopySides() { return copySides; }
    public void setCopySides(String copySides) { this.copySides = copySides; }
    public boolean isAllowShortSelling() { return allowShortSelling; }
    public void setAllowShortSelling(boolean allowShortSelling) { this.allowShortSelling = allowShortSelling; }
    public double getPriceTolerancePct() { return priceTolerancePct; }
    public void setPriceTolerancePct(double priceTolerancePct) { this.priceTolerancePct = priceTolerancePct; }
}
