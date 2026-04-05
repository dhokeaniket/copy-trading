package com.copytrading.child.dto;

import java.util.List;
import java.util.UUID;

public class BulkSubscribeRequest {
    private List<MasterSub> masters;

    public List<MasterSub> getMasters() { return masters; }
    public void setMasters(List<MasterSub> masters) { this.masters = masters; }

    public static class MasterSub {
        private UUID masterId;
        private UUID brokerAccountId;
        private Double scalingFactor;

        public UUID getMasterId() { return masterId; }
        public void setMasterId(UUID masterId) { this.masterId = masterId; }
        public UUID getBrokerAccountId() { return brokerAccountId; }
        public void setBrokerAccountId(UUID brokerAccountId) { this.brokerAccountId = brokerAccountId; }
        public Double getScalingFactor() { return scalingFactor; }
        public void setScalingFactor(Double scalingFactor) { this.scalingFactor = scalingFactor; }
    }
}
