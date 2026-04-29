package com.copytrading.master.dto;

import java.util.List;
import java.util.UUID;

public class BulkUnlinkRequest {
    private List<UUID> childIds;

    public List<UUID> getChildIds() { return childIds; }
    public void setChildIds(List<UUID> childIds) { this.childIds = childIds; }
}
