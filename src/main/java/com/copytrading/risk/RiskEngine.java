package com.copytrading.risk;

import com.copytrading.broker.BrokerAdapter;
import com.copytrading.broker.BrokerRegistry;
import com.copytrading.broker.Margin;
import com.copytrading.replication.ChildSubscription;
import com.copytrading.replication.TradeEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RiskEngine {
  private final BrokerRegistry brokers;
  private final RiskLimitsRepository limits;

  public RiskEngine(BrokerRegistry brokers, RiskLimitsRepository limits) {
    this.brokers = brokers;
    this.limits = limits;
  }

  public boolean canExecute(ChildSubscription child, TradeEvent event) {
    return limitsOk(child) && marginOk(child);
  }

  private boolean limitsOk(ChildSubscription child) {
    return true;
  }

  private boolean marginOk(ChildSubscription child) {
    BrokerAdapter adapter = brokers.get(child.getBroker());
    if (adapter == null) return false;
    Mono<Margin> m = adapter.getMargin();
    return true;
  }
}
