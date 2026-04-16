package com.copytrading.trade;

import com.copytrading.broker.BrokerAccountService;
import com.copytrading.engine.CopyEngineService;
import com.copytrading.engine.CopyTradeRequest;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.notification.NotificationService;
import com.copytrading.subscription.SubscriptionRepository;
import com.copytrading.ws.TradeUpdatesHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
public class TradeEngineService {
    private static final Logger log = LoggerFactory.getLogger(TradeEngineService.class);

    private final TradeRepository trades;
    private final BrokerAccountService brokerService;
    private final CopyEngineService copyEngine;
    private final CopyLogRepository copyLogs;
    private final SubscriptionRepository subs;
    private final NotificationService notifications;
    private final TradeUpdatesHub hub;

    public TradeEngineService(TradeRepository trades, BrokerAccountService brokerService,
                              CopyEngineService copyEngine, CopyLogRepository copyLogs,
                              SubscriptionRepository subs, NotificationService notifications,
                              TradeUpdatesHub hub) {
        this.trades = trades;
        this.brokerService = brokerService;
        this.copyEngine = copyEngine;
        this.copyLogs = copyLogs;
        this.subs = subs;
        this.notifications = notifications;
        this.hub = hub;
    }

    /** 6.1 POST /trades/execute — Place order + auto-replicate to children if master */
    public Mono<Map<String, Object>> executeTrade(UUID userId, String role, Map<String, Object> body) {
        UUID brokerAccountId = UUID.fromString(body.get("brokerAccountId").toString());
        String instrument = (String) body.get("instrument");
        String exchange = (String) body.getOrDefault("exchange", "NSE");
        String segment = (String) body.getOrDefault("segment", "EQUITY");
        String orderType = (String) body.getOrDefault("orderType", "MARKET");
        String txnType = (String) body.get("transactionType");
        int qty = ((Number) body.get("quantity")).intValue();
        double price = body.containsKey("price") ? ((Number) body.get("price")).doubleValue() : 0;
        String product = (String) body.getOrDefault("product", "MIS");
        String validity = (String) body.getOrDefault("validity", "DAY");

        // Place order on broker
        return brokerService.closePosition(brokerAccountId, userId,
                        Map.of("symbol", instrument, "qty", qty, "type", txnType, "product", product))
                .flatMap(brokerResp -> {
                    String brokerOrderId = brokerResp.getOrDefault("response", "").toString();

                    // Save trade record
                    Trade t = new Trade();
                    t.setUserId(userId);
                    t.setBrokerAccountId(brokerAccountId);
                    t.setBrokerOrderId(brokerOrderId.length() > 100 ? brokerOrderId.substring(0, 100) : brokerOrderId);
                    t.setInstrument(instrument);
                    t.setExchange(exchange);
                    t.setSegment(segment);
                    t.setOrderType(orderType);
                    t.setTransactionType(txnType);
                    t.setQuantity(qty);
                    t.setPrice(price);
                    t.setProduct(product);
                    t.setValidity(validity);
                    t.setStatus("EXECUTED");
                    t.setPlacedAt(Instant.now());
                    t.setExecutedAt(Instant.now());

                    return trades.save(t).flatMap(saved -> {
                        // Publish WebSocket event
                        hub.publish("{\"event\":\"TRADE_EXECUTED\",\"tradeId\":\"" + saved.getId() +
                                "\",\"instrument\":\"" + instrument + "\",\"status\":\"EXECUTED\"}");

                        // If MASTER, auto-replicate to children
                        if ("MASTER".equals(role)) {
                            CopyTradeRequest req = new CopyTradeRequest();
                            req.setSymbol(instrument);
                            req.setQty(qty);
                            req.setSide(txnType);
                            req.setProduct(product);
                            req.setOrderType(orderType);
                            req.setPrice(price);
                            return copyEngine.copyTrade(userId, req).map(copyResult -> {
                                int replCount = (int) copyResult.getOrDefault("childrenTotal", 0);
                                saved.setReplicationsTriggered(replCount);
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("tradeId", saved.getId());
                                r.put("brokerOrderId", saved.getBrokerOrderId());
                                r.put("status", "EXECUTED");
                                r.put("replicationsTriggered", replCount);
                                r.put("replicationDetails", copyResult.get("results"));
                                return r;
                            });
                        }
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("tradeId", saved.getId());
                        r.put("brokerOrderId", saved.getBrokerOrderId());
                        r.put("status", "EXECUTED");
                        r.put("replicationsTriggered", 0);
                        return Mono.just(r);
                    });
                })
                .onErrorResume(e -> {
                    hub.publish("{\"event\":\"TRADE_FAILED\",\"instrument\":\"" + instrument + "\",\"error\":\"" + e.getMessage() + "\"}");
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Trade failed: " + e.getMessage()));
                });
    }

    /** 6.2 GET /trades — list user's trades */
    public Mono<Map<String, Object>> listTrades(UUID userId, String status) {
        var flux = (status != null && !status.isBlank())
                ? trades.findByUserIdAndStatusOrderByPlacedAtDesc(userId, status)
                : trades.findByUserIdOrderByPlacedAtDesc(userId);
        return flux.collectList().map(list -> Map.<String, Object>of("trades", list));
    }

    /** 6.3 GET /trades/:tradeId */
    public Mono<Trade> getTrade(UUID tradeId, UUID userId) {
        return trades.findById(tradeId)
                .filter(t -> t.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade not found")));
    }

    /** 6.4 DELETE /trades/:tradeId/cancel */
    public Mono<Map<String, Object>> cancelTrade(UUID tradeId, UUID userId) {
        return trades.findById(tradeId)
                .filter(t -> t.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade not found")))
                .flatMap(t -> {
                    if (!"PENDING".equals(t.getStatus()) && !"OPEN".equals(t.getStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only OPEN/PENDING orders can be cancelled"));
                    }
                    return brokerService.cancelOrder(t.getBrokerAccountId(), userId, t.getBrokerOrderId())
                            .flatMap(resp -> {
                                t.setStatus("CANCELLED");
                                t.setCancelledAt(Instant.now());
                                return trades.save(t).map(s -> {
                                    hub.publish("{\"event\":\"TRADE_CANCELLED\",\"tradeId\":\"" + s.getId() + "\"}");
                                    Map<String, Object> r = new LinkedHashMap<>();
                                    r.put("message", "Order cancelled");
                                    r.put("brokerResponse", resp.toString());
                                    return r;
                                });
                            });
                });
    }

    /** 6.5 GET /trades/:tradeId/replications */
    public Mono<Map<String, Object>> getTradeReplications(UUID tradeId, UUID userId) {
        return trades.findById(tradeId)
                .filter(t -> t.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade not found")))
                .flatMap(t -> copyLogs.findByMasterId(userId).collectList()
                        .map(logs -> {
                            var filtered = logs.stream()
                                    .filter(l -> t.getInstrument().equals(l.getSymbol()))
                                    .toList();
                            return Map.<String, Object>of("replications", filtered);
                        }));
    }

    /** 6.6 GET /trades/open-positions */
    public Mono<Map<String, Object>> getOpenPositions(UUID userId, UUID brokerAccountId) {
        if (brokerAccountId != null) {
            return brokerService.getPositions(brokerAccountId, userId);
        }
        return trades.findByUserIdAndStatus(userId, "EXECUTED").collectList()
                .map(list -> Map.<String, Object>of("positions", list));
    }

    /** 6.7 POST /trades/basket */
    public Mono<Map<String, Object>> placeBasketOrder(UUID userId, String role, Map<String, Object> body) {
        UUID brokerAccountId = UUID.fromString(body.get("brokerAccountId").toString());
        String basketName = (String) body.getOrDefault("basketName", "Basket");
        List<Map<String, Object>> orders = (List<Map<String, Object>>) body.get("orders");
        if (orders == null || orders.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "orders array required"));
        }
        return reactor.core.publisher.Flux.fromIterable(orders)
                .flatMap(order -> {
                    order.put("brokerAccountId", brokerAccountId.toString());
                    return executeTrade(userId, role, order)
                            .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));
                })
                .collectList()
                .map(results -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("basketId", UUID.randomUUID());
                    r.put("basketName", basketName);
                    r.put("trades", results);
                    return r;
                });
    }
}
