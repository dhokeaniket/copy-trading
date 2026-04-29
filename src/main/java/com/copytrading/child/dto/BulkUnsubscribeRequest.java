package com.copytrading.child.dto;

import java.util.List;
import java.util.UUID;

public class BulkUnsubscribeRequest {
    private List<UUID> masterIds;

    public List<UUID> getMasterIds() { return masterIds; }
    public void setMasterIds(List<UUID> masterIds) { this.masterIds = masterIds; }
}
