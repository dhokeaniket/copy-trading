package com.copytrading.replication;

import com.copytrading.broker.BrokerAdapter;
import com.copytrading.broker.BrokerRegistry;
import com.copytrading.broker.OrderRequest;
import com.copytrading.risk.RiskEngine;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class TradeReplicationService {
  private final BrokerRegistry brokers;
  private final RiskEngine risk;

  public TradeReplicationService(BrokerRegistry brokers, RiskEngine risk) {
    this.brokers = brokers;
    this.risk = risk;
  }

  public Mono<Void> replicateTrade(TradeEvent event, List<ChildSubscription> children) {
    return Flux.fromIterable(children)
        .flatMap(child -> replicateToChild(event, child))
        .then();
  }

  private Mono<String> replicateToChild(TradeEvent event, ChildSubscription child) {
    if (!risk.canExecute(child, event)) return Mono.error(new RuntimeException("risk_blocked"));
    OrderRequest scaled = scale(event.getOrder(), child.getScale());
    BrokerAdapter adapter = brokers.get(child.getBroker());
    if (adapter == null) return Mono.error(new RuntimeException("broker_missing"));
    if ("PLACE".equals(event.getType())) return adapter.placeOrder(scaled);
    if ("CANCEL".equals(event.getType())) return adapter.cancelOrder(event.getOrderId()).then(Mono.just("ok"));
    return Mono.error(new RuntimeException("unknown_event"));
  }

  private OrderRequest scale(OrderRequest req, double scale) {
    OrderRequest r = new OrderRequest();
    r.setSymbol(req.getSymbol());
    r.setSide(req.getSide());
    r.setOrderType(req.getOrderType());
    r.setPrice(req.getPrice());
    r.setQuantity((int) Math.max(1, Math.round(req.getQuantity() * scale)));
    return r;
  }
}
