package com.copytrading.master.dto;

import java.util.List;

public class ActiveAccountsRequest {
    private List<String> brokerAccountIds;

    public List<String> getBrokerAccountIds() { return brokerAccountIds; }
    public void setBrokerAccountIds(List<String> brokerAccountIds) { this.brokerAccountIds = brokerAccountIds; }
}
