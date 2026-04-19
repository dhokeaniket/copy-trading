package com.copytrading.engine;

import com.copytrading.alert.BalanceAlertService;
import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.broker.PlatformBrokerConfig;
import com.copytrading.broker.dhan.DhanApiClient;
import com.copytrading.broker.dto.BrokerAccountDto;
import com.copytrading.broker.fyers.FyersApiClient;
import com.copytrading.broker.groww.GrowwApiClient;
import com.copytrading.broker.upstox.UpstoxApiClient;
import com.copytrading.broker.zerodha.ZerodhaApiClient;
import com.copytrading.logs.CopyLog;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.notification.NotificationService;
import com.copytrading.subscription.Subscription;
import com.copytrading.subscription.SubscriptionRepository;
import com.copytrading.trade.Trade;
import com.copytrading.trade.TradeRepository;
import com.copytrading.ws.TradeUpdatesHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

/**
 * Core trade copy engine.
 *
 * Two modes:
 * 1. Manual: Master triggers POST /api/v1/engine/copy-trade with order details
 * 2. Polling: OrderPollingService detects new master orders and calls copyTrade()
 *
 * Flow:
 *   Master order → find ACTIVE children → for each child:
 *     → check balance → scale qty → place order on child's broker → log → notify
 */
@Service
public class CopyEngineService {

    private static final Logger log = LoggerFactory.getLogger(CopyEngineService.class);

    private final SubscriptionRepository subs;
    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;
    private final CopyLogRepository copyLogs;
    private final NotificationService notifications;
    private final BalanceAlertService balanceAlert;
    private final TradeRepository tradeRepo;
    private final TradeUpdatesHub hub;
    private final SymbolMapper symbolMapper;
    private final GrowwApiClient growwClient;
    private final ZerodhaApiClient zerodhaClient;
    private final FyersApiClient fyersClient;
    private final UpstoxApiClient upstoxClient;
    private final DhanApiClient dhanClient;
    private final PlatformBrokerConfig platformConfig;

    public CopyEngineService(SubscriptionRepository subs,
                             BrokerAccountRepository brokerRepo,
                             BrokerAccountService brokerService,
                             CopyLogRepository copyLogs,
                             NotificationService notifications,
                             BalanceAlertService balanceAlert,
                             TradeRepository tradeRepo,
                             TradeUpdatesHub hub,
                             SymbolMapper symbolMapper,
                             GrowwApiClient growwClient,
                             ZerodhaApiClient zerodhaClient,
                             FyersApiClient fyersClient,
                             UpstoxApiClient upstoxClient,
                             DhanApiClient dhanClient,
                             PlatformBrokerConfig platformConfig) {
        this.subs = subs;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.copyLogs = copyLogs;
        this.notifications = notifications;
        this.balanceAlert = balanceAlert;
        this.tradeRepo = tradeRepo;
        this.hub = hub;
        this.symbolMapper = symbolMapper;
        this.growwClient = growwClient;
        this.zerodhaClient = zerodhaClient;
        this.fyersClient = fyersClient;
        this.upstoxClient = upstoxClient;
        this.dhanClient = dhanClient;
        this.platformConfig = platformConfig;
    }

    /**
     * Copy a trade from master to all active children.
     * Called by manual trigger or polling engine.
     */
    public Mono<Map<String, Object>> copyTrade(UUID masterId, CopyTradeRequest req) {
        log.info("COPY_ENGINE_START master={} symbol={} qty={} side={}",
                masterId, req.getSymbol(), req.getQty(), req.getSide());

        return subs.findByMasterIdAndCopyingStatus(masterId, "ACTIVE")
                .collectList()
                .flatMap(children -> {
                    if (children.isEmpty()) {
                        return Mono.just(Map.<String, Object>of(
                                "message", "No active children to copy to",
                                "childrenCount", 0));
                    }
                    return Flux.fromIterable(children)
                            .flatMap(sub -> replicateToChild(masterId, sub, req))
                            .collectList()
                            .flatMap(results -> {
                                long success = results.stream().filter(r -> "SUCCESS".equals(r.get("status"))).count();
                                long failed = results.stream().filter(r -> "FAILED".equals(r.get("status"))).count();

                                // Notify master
                                String masterMsg = "Trade " + req.getSide() + " " + req.getSymbol() +
                                        " ×" + req.getQty() + " copied to " + success + "/" + children.size() + " children";
                                notifications.push(masterId, "Trade Copied", masterMsg, "TRADE_COPIED")
                                        .subscribe();

                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("message", "Trade copy completed");
                                r.put("symbol", req.getSymbol());
                                r.put("side", req.getSide());
                                r.put("masterQty", req.getQty());
                                r.put("childrenTotal", children.size());
                                r.put("success", success);
                                r.put("failed", failed);
                                r.put("results", results);
                                return Mono.just(r);
                            });
                });
    }

    /**
     * Replicate a single trade to one child.
     */
    private Mono<Map<String, Object>> replicateToChild(UUID masterId, Subscription sub, CopyTradeRequest req) {
        UUID childId = sub.getChildId();
        UUID brokerAccountId = sub.getBrokerAccountId();
        double scale = sub.getScalingFactor();
        int scaledQty = (int) Math.max(1, Math.round(req.getQty() * scale));

        if (brokerAccountId == null) {
            return logAndReturn(masterId, childId, req, "FAILED", "No broker account linked", null);
        }

        return brokerRepo.findById(brokerAccountId)
                .switchIfEmpty(Mono.defer(() ->
                        logAndReturn(masterId, childId, req, "FAILED", "Broker account not found", null)
                                .then(Mono.empty())))
                .flatMap(account -> {
                    if (!account.isSessionActive() || account.getAccessToken() == null) {
                        return logAndReturn(masterId, childId, req, "FAILED",
                                "Broker session inactive. Child needs to re-login.", account.getBrokerId());
                    }

                    // Place order DIRECTLY — let broker handle margin check (fastest path)
                    // Balance alert sent async after order attempt
                    return placeOrderOnBroker(account, req.getSymbol(), scaledQty,
                            req.getSide(), req.getProduct(), req.getOrderType(), req.getPrice())
                            .flatMap(response -> {
                                String orderId = extractOrderId(response);
                                log.info("COPY_ORDER_PLACED child={} broker={} orderId={} symbol={} qty={}",
                                        childId, account.getBrokerId(), orderId, req.getSymbol(), scaledQty);

                                // Save child's trade to trades table
                                Trade childTrade = new Trade();
                                childTrade.setUserId(childId);
                                childTrade.setBrokerAccountId(brokerAccountId);
                                childTrade.setBrokerOrderId(orderId.length() > 100 ? orderId.substring(0, 100) : orderId);
                                childTrade.setInstrument(req.getSymbol());
                                childTrade.setExchange("NSE");
                                childTrade.setSegment("EQUITY");
                                childTrade.setOrderType(req.getOrderType() != null ? req.getOrderType() : "MARKET");
                                childTrade.setTransactionType(req.getSide());
                                childTrade.setQuantity(scaledQty);
                                childTrade.setPrice(req.getPrice());
                                childTrade.setProduct(req.getProduct() != null ? req.getProduct() : "MIS");
                                childTrade.setStatus("EXECUTED");
                                childTrade.setPlacedAt(Instant.now());
                                childTrade.setExecutedAt(Instant.now());
                                tradeRepo.save(childTrade).subscribe();

                                // Notify child
                                notifications.push(childId,
                                        "Trade Copied: " + req.getSide() + " " + req.getSymbol(),
                                        req.getSide() + " " + req.getSymbol() + " ×" + scaledQty +
                                                " (scaled from " + req.getQty() + " × " + scale + ")",
                                        "TRADE_EXECUTED"
                                ).subscribe();

                                // Check balance AFTER trade (async, non-blocking)
                                balanceAlert.checkAndAlert(childId, brokerAccountId).subscribe();

                                return logAndReturn(masterId, childId, req, "SUCCESS",
                                        "Order placed: " + orderId, account.getBrokerId());
                            })
                            .onErrorResume(e -> {
                                log.error("COPY_ORDER_FAILED child={} broker={} error={}",
                                        childId, account.getBrokerId(), e.getMessage());

                                // If 401/session expired, push WebSocket + notification
                                if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                                    hub.publish("{\"event\":\"SESSION_EXPIRED\",\"data\":{\"childId\":\"" + childId + "\",\"broker\":\"" + account.getBrokerId() + "\",\"accountId\":\"" + brokerAccountId + "\"}}");
                                    notifications.push(childId, "Broker session expired", "Your " + BrokerAccountDto.from(account).getBrokerName() + " session has expired. Re-login to resume copy trading.", "SESSION_EXPIRED").subscribe();
                                }

                                notifications.push(childId,
                                        "⚠️ Trade Copy Failed",
                                        "Failed to copy " + req.getSide() + " " + req.getSymbol() +
                                                ": " + e.getMessage(),
                                        "TRADE_FAILED"
                                ).subscribe();

                                // Check balance async (might be the reason it failed)
                                balanceAlert.checkAndAlert(childId, brokerAccountId).subscribe();

                                return logAndReturn(masterId, childId, req, "FAILED",
                                        "Order failed: " + e.getMessage(), account.getBrokerId());
                            });
                });
    }

    /**
     * Place order on the child's broker using the real API client.
     */
    private Mono<Map> placeOrderOnBroker(BrokerAccount account, String symbol, int qty,
                                          String side, String product, String orderType, double price) {
        symbol = symbolMapper.translate(symbol, "GROWW", account.getBrokerId()); // Best effort translation
        String token = account.getAccessToken();
        String prod = product != null ? product : "MIS";
        String oType = orderType != null ? orderType : "MARKET";

        switch (account.getBrokerId()) {
            case "GROWW":
                return growwClient.placeOrder(token, Map.of(
                        "symbol", symbol, "qty", qty,
                        "type", side, "product", prod,
                        "order_type", oType, "price", price
                ));
            case "ZERODHA": {
                String apiKey = platformConfig.getZerodha().getApiKey();
                return zerodhaClient.placeOrder(apiKey, token, Map.of(
                        "tradingsymbol", symbol, "quantity", qty,
                        "transaction_type", side, "product", prod,
                        "order_type", oType, "exchange", "NSE"
                ));
            }
            case "FYERS": {
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + token;
                int fSide = "BUY".equalsIgnoreCase(side) ? 1 : -1;
                return fyersClient.placeOrder(fyersAuth, Map.of(
                        "symbol", "NSE:" + symbol + "-EQ", "qty", qty,
                        "side", fSide, "productType", prod,
                        "type", "MARKET".equals(oType) ? 2 : 1,
                        "limitPrice", price, "stopPrice", 0
                ));
            }
            case "UPSTOX":
                return upstoxClient.placeOrder(token, Map.of(
                        "instrument_token", symbol, "quantity", qty,
                        "transaction_type", side, "product", prod,
                        "order_type", oType, "price", price
                ));
            case "DHAN":
                return dhanClient.placeOrder(token, Map.of(
                        "tradingSymbol", symbol, "quantity", qty,
                        "transactionType", side, "productType", prod,
                        "orderType", oType, "exchangeSegment", "NSE_EQ"
                ));
            default:
                return Mono.error(new RuntimeException("Unsupported broker: " + account.getBrokerId()));
        }
    }

    private String extractOrderId(Map response) {
        if (response == null) return "unknown";
        // Different brokers return orderId in different fields
        for (String key : List.of("order_id", "orderId", "data", "id", "payload")) {
            Object val = response.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
            if (val instanceof Map m) {
                Object inner = m.get("order_id");
                if (inner == null) inner = m.get("orderId");
                if (inner != null) return inner.toString();
            }
        }
        return response.toString().substring(0, Math.min(100, response.toString().length()));
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker) {
        return logAndReturn(masterId, childId, req, status, message, broker, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason) {
        // Save to copy_logs
        CopyLog cl = new CopyLog();
        cl.setMasterId(masterId);
        cl.setChildId(childId);
        cl.setSymbol(req.getSymbol());
        cl.setQty(req.getQty());
        cl.setTradeType(req.getSide());
        cl.setMasterStatus("EXECUTED");
        cl.setChildStatus(status);
        cl.setErrorMessage("FAILED".equals(status) ? message : null);
        if ("SKIPPED".equals(status) && skipReason != null) {
            cl.setSkipReason(skipReason);
        }
        cl.setCreatedAt(Instant.now());

        return copyLogs.save(cl)
                .onErrorResume(e -> {
                    log.warn("COPY_LOG_SAVE_FAILED: {}", e.getMessage());
                    return Mono.just(cl);
                })
                .map(saved -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("childId", childId.toString());
                    r.put("status", status);
                    r.put("message", message);
                    if (broker != null) r.put("broker", broker);
                    r.put("scaledQty", req.getQty());
                    if ("SKIPPED".equals(status) && skipReason != null) {
                        r.put("skipReason", skipReason);
                    }
                    return r;
                });
    }

    /**
     * Get engine status — is polling active, how many masters being tracked, etc.
     */
    public Mono<Map<String, Object>> getStatus() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("engineStatus", "ACTIVE");
        r.put("pollingEnabled", false);
        r.put("pollingIntervalSeconds", 1);
        r.put("supportedBrokers", List.of("GROWW", "ZERODHA", "FYERS", "UPSTOX", "DHAN"));
        r.put("modes", List.of("manual", "polling", "postback", "websocket"));
        r.put("detectionMethod", Map.of(
                "ZERODHA", "postback (~100ms)",
                "FYERS", "websocket (~50ms)",
                "UPSTOX", "websocket (~50ms)",
                "DHAN", "polling (1s)",
                "GROWW", "polling (1s)"
        ));
        return Mono.just(r);
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
