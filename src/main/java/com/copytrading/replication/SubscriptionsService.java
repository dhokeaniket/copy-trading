package com.copytrading.replication;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SubscriptionsService {
  public List<ChildSubscription> findSubscribedChildren(Long masterId) {
    return List.of();
  }
}
