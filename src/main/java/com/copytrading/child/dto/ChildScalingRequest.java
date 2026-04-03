package com.copytrading.child.dto;

import java.util.UUID;

public class ChildScalingRequest {
    private UUID masterId;
    private double scalingFactor;

    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public double getScalingFactor() { return scalingFactor; }
    public void setScalingFactor(double scalingFactor) { this.scalingFactor = scalingFactor; }
}
