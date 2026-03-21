package com.copytrading.replication;

import com.copytrading.broker.BrokerAdapter;
import com.copytrading.broker.BrokerRegistry;
import com.copytrading.broker.OrderRequest;
import com.copytrading.logs.TradeLog;
import com.copytrading.logs.TradeLogService;
import com.copytrading.risk.RiskEngine;
import com.copytrading.ws.TradeUpdatesHub;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class TradeReplicationService {
  private final BrokerRegistry brokers;
  private final RiskEngine risk;
  private final TradeLogService tradeLogs;
  private final TradeUpdatesHub hub;

  public TradeReplicationService(BrokerRegistry brokers, RiskEngine risk, TradeLogService tradeLogs, TradeUpdatesHub hub) {
    this.brokers = brokers;
    this.risk = risk;
    this.tradeLogs = tradeLogs;
    this.hub = hub;
  }

  public Mono<Void> replicateTrade(TradeEvent event, List<ChildSubscription> children) {
    return Flux.fromIterable(children)
        .flatMap(child -> replicateToChild(event, child)
            .flatMap(orderId -> logAndPublish(event, child, "REPLICATED", "SUCCESS", orderId))
            .onErrorResume(e -> logAndPublish(event, child, "REPLICATED", "FAILED", e.getMessage()).then(Mono.empty())))
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

  private Mono<TradeLog> logAndPublish(TradeEvent event, ChildSubscription child, String type, String status, String message) {
    TradeLog log = new TradeLog();
    log.setMasterId(event.getMasterId());
    log.setChildId(child.getChildId());
    log.setType(type);
    log.setStatus(status);
    log.setMessage(message);
    log.setBroker(child.getBroker() == null ? null : child.getBroker().name());
    hub.publish("{\"type\":\"" + type + "\",\"status\":\"" + status + "\",\"masterId\":" + event.getMasterId() + ",\"childId\":" + child.getChildId() + "}");
    return tradeLogs.log(log);
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
