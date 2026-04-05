package com.copytrading.master.dto;

import java.util.List;
import java.util.UUID;

public class BulkLinkRequest {
    private List<ChildLink> children;

    public List<ChildLink> getChildren() { return children; }
    public void setChildren(List<ChildLink> children) { this.children = children; }

    public static class ChildLink {
        private UUID childId;
        private Double scalingFactor;

        public UUID getChildId() { return childId; }
        public void setChildId(UUID childId) { this.childId = childId; }
        public Double getScalingFactor() { return scalingFactor; }
        public void setScalingFactor(Double scalingFactor) { this.scalingFactor = scalingFactor; }
    }
}
