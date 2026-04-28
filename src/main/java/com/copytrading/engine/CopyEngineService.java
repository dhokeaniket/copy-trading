package com.copytrading.engine;

import com.copytrading.alert.BalanceAlertService;
import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.broker.PlatformBrokerConfig;
import com.copytrading.broker.angelone.AngelOneApiClient;
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
    private final AngelOneApiClient angelOneClient;
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
                             AngelOneApiClient angelOneClient,
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
        this.angelOneClient = angelOneClient;
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
            // Fallback: try to find child's first active broker account and update the subscription
            return brokerRepo.findByUserId(childId)
                    .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                    .next()
                    .flatMap(account -> {
                        // Auto-fix the subscription with the found broker account
                        sub.setBrokerAccountId(account.getId());
                        return subs.save(sub)
                                .then(placeOrderOnChild(masterId, childId, account, req, scaledQty, scale));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        logAndReturn(masterId, childId, req, "FAILED",
                                "No broker account linked. Child needs to link a broker account and re-subscribe.", null)
                    ));
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
                    return placeOrderOnChild(masterId, childId, account, req, scaledQty, scale);
                });
    }

    /**
     * Place order on child's broker and handle success/failure logging.
     */
    private Mono<Map<String, Object>> placeOrderOnChild(UUID masterId, UUID childId,
                                                         BrokerAccount account, CopyTradeRequest req,
                                                         int scaledQty, double scale) {
        UUID brokerAccountId = account.getId();
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
    }

    /**
     * Place order on the child's broker using the real API client.
     * Each broker has its own specific order format — these match their official API docs.
     */
    private Mono<Map> placeOrderOnBroker(BrokerAccount account, String symbol, int qty,
                                          String side, String product, String orderType, double price) {
        symbol = symbolMapper.translate(symbol, "GROWW", account.getBrokerId());
        String token = account.getAccessToken();
        String prod = product != null ? product : "MIS";
        String oType = orderType != null ? orderType : "MARKET";
        boolean isMarket = oType.equalsIgnoreCase("MARKET");
        boolean isFnO = symbolMapper.isFnO(symbol);

        switch (account.getBrokerId()) {
            case "GROWW": {
                // Groww API: POST /v1/order/create (JSON body)
                Map<String, Object> growwBody = new java.util.LinkedHashMap<>();
                growwBody.put("trading_symbol", symbol);
                growwBody.put("quantity", qty);
                growwBody.put("price", isMarket ? 0 : price);
                growwBody.put("trigger_price", 0);
                growwBody.put("validity", "DAY");
                growwBody.put("exchange", "NSE");
                growwBody.put("segment", isFnO ? "FNO" : "CASH");
                growwBody.put("product", prod);
                growwBody.put("order_type", isMarket ? "MARKET" : "LIMIT");
                growwBody.put("transaction_type", side.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                growwBody.put("order_reference_id", "COPY-" + System.currentTimeMillis());
                return growwClient.placeOrder(token, growwBody);
            }
            case "ZERODHA": {
                // Zerodha Kite API: POST /orders/regular (form-urlencoded)
                // Required: tradingsymbol, exchange, transaction_type, order_type, quantity, product
                // For MARKET: price=0, trigger_price=0
                // For LIMIT: price=actual, trigger_price=0
                String apiKey = platformConfig.getZerodha().getApiKey();
                String exchange = isFnO ? "NFO" : "NSE";
                Map<String, Object> zerodhaBody = new java.util.LinkedHashMap<>();
                zerodhaBody.put("tradingsymbol", symbol);
                zerodhaBody.put("exchange", exchange);
                zerodhaBody.put("transaction_type", side.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                zerodhaBody.put("order_type", isMarket ? "MARKET" : "LIMIT");
                zerodhaBody.put("quantity", qty);
                zerodhaBody.put("product", prod);
                zerodhaBody.put("validity", "DAY");
                if (!isMarket) {
                    zerodhaBody.put("price", price);
                }
                return zerodhaClient.placeOrder(apiKey, token, zerodhaBody);
            }
            case "FYERS": {
                // Fyers API v3: POST /orders/sync (JSON body)
                // symbol format: "NSE:SYMBOL-EQ" for equity, "NSE:SYMBOL..." for F&O
                // side: 1=BUY, -1=SELL
                // type: 1=LIMIT, 2=MARKET, 3=SL, 4=SL-M
                // productType: INTRADAY, CNC, MARGIN, BO, CO
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + token;
                int fSide = "BUY".equalsIgnoreCase(side) ? 1 : -1;
                int fType = isMarket ? 2 : 1;
                String fyersSymbol = isFnO ? symbol : "NSE:" + symbol + "-EQ";
                String fyersProd = prod.equalsIgnoreCase("MIS") ? "INTRADAY"
                        : prod.equalsIgnoreCase("CNC") ? "CNC"
                        : prod.equalsIgnoreCase("NRML") ? "MARGIN" : "INTRADAY";
                Map<String, Object> fyersBody = new java.util.LinkedHashMap<>();
                fyersBody.put("symbol", fyersSymbol);
                fyersBody.put("qty", qty);
                fyersBody.put("type", fType);
                fyersBody.put("side", fSide);
                fyersBody.put("productType", fyersProd);
                fyersBody.put("limitPrice", isMarket ? 0 : price);
                fyersBody.put("stopPrice", 0);
                fyersBody.put("validity", "DAY");
                fyersBody.put("disclosedQty", 0);
                fyersBody.put("offlineOrder", false);
                fyersBody.put("stopLoss", 0);
                fyersBody.put("takeProfit", 0);
                return fyersClient.placeOrder(fyersAuth, fyersBody);
            }
            case "UPSTOX": {
                // Upstox API v2: POST /v2/order/place (JSON body)
                // instrument_token format: "NSE_EQ|ISIN" or "NSE_FO|TOKEN"
                // product: "I" (intraday), "D" (delivery/CNC), "MTF"
                // order_type: MARKET, LIMIT, SL, SL-M
                // transaction_type: BUY, SELL
                String upstoxProd = prod.equalsIgnoreCase("MIS") ? "I"
                        : prod.equalsIgnoreCase("CNC") ? "D"
                        : prod.equalsIgnoreCase("NRML") ? "D" : "I";
                String upstoxSymbol = symbol;
                // If symbol doesn't already have exchange prefix, add it
                if (!symbol.contains("|")) {
                    upstoxSymbol = (isFnO ? "NSE_FO|" : "NSE_EQ|") + symbol;
                }
                Map<String, Object> upstoxBody = new java.util.LinkedHashMap<>();
                upstoxBody.put("quantity", qty);
                upstoxBody.put("product", upstoxProd);
                upstoxBody.put("validity", "DAY");
                upstoxBody.put("price", isMarket ? 0 : price);
                upstoxBody.put("tag", "COPY-" + System.currentTimeMillis());
                upstoxBody.put("instrument_token", upstoxSymbol);
                upstoxBody.put("order_type", isMarket ? "MARKET" : "LIMIT");
                upstoxBody.put("transaction_type", side.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                upstoxBody.put("disclosed_quantity", 0);
                upstoxBody.put("trigger_price", 0);
                upstoxBody.put("is_amo", false);
                return upstoxClient.placeOrder(token, upstoxBody);
            }
            case "DHAN": {
                // Dhan API v2: POST /v2/orders (JSON body)
                // Requires securityId (numeric) — we try to resolve via search, fallback to symbol
                // exchangeSegment: NSE_EQ, NSE_FNO, BSE_EQ, etc.
                // transactionType: BUY, SELL
                // orderType: MARKET, LIMIT, SL, SLM
                // productType: CNC, INTRADAY, MARGIN, CO, BO
                String dhanExchange = isFnO ? "NSE_FNO" : "NSE_EQ";
                String dhanTxnType = "BUY".equalsIgnoreCase(side) ? "BUY" : "SELL";
                String dhanOrderType = isMarket ? "MARKET" : "LIMIT";
                String dhanProd = prod.equalsIgnoreCase("CNC") ? "CNC"
                        : prod.equalsIgnoreCase("NRML") ? "MARGIN"
                        : "INTRADAY";
                Map<String, Object> dhanBody = new java.util.LinkedHashMap<>();
                dhanBody.put("transactionType", dhanTxnType);
                dhanBody.put("exchangeSegment", dhanExchange);
                dhanBody.put("productType", dhanProd);
                dhanBody.put("orderType", dhanOrderType);
                dhanBody.put("validity", "DAY");
                dhanBody.put("tradingSymbol", symbol);
                dhanBody.put("quantity", qty);
                dhanBody.put("price", isMarket ? 0 : price);
                dhanBody.put("triggerPrice", 0);
                dhanBody.put("disclosedQuantity", 0);
                dhanBody.put("afterMarketOrder", false);
                return dhanClient.searchSecurityId(token, symbol, dhanExchange)
                        .flatMap(secId -> {
                            if (secId != null && !secId.isBlank()) {
                                dhanBody.put("securityId", secId);
                            }
                            return dhanClient.placeOrder(token, dhanBody);
                        })
                        .switchIfEmpty(dhanClient.placeOrder(token, dhanBody));
            }
            case "ANGELONE": {
                // Angel One SmartAPI: POST /rest/secure/angelbroking/order/v1/placeOrder (JSON body)
                // Required: variety, tradingsymbol, symboltoken, transactiontype, exchange,
                //           ordertype, producttype, duration, price, squareoff, stoploss, quantity
                // variety: NORMAL, STOPLOSS, AMO, ROBO
                // producttype: INTRADAY, DELIVERY, CARRYFORWARD, BO, CO
                // ordertype: MARKET, LIMIT, STOPLOSS_LIMIT, STOPLOSS_MARKET
                String apiKey = platformConfig.getAngelone().getApiKey();
                String exchange = isFnO ? "NFO" : "NSE";
                String angelProd = prod.equalsIgnoreCase("MIS") ? "INTRADAY"
                        : prod.equalsIgnoreCase("CNC") ? "DELIVERY"
                        : prod.equalsIgnoreCase("NRML") ? "CARRYFORWARD" : "INTRADAY";
                String angelOrderType = isMarket ? "MARKET" : "LIMIT";
                // Angel One uses "-EQ" suffix for equity symbols
                String angelSymbol = isFnO ? symbol : symbol + "-EQ";
                Map<String, Object> angelBody = new java.util.LinkedHashMap<>();
                angelBody.put("variety", "NORMAL");
                angelBody.put("tradingsymbol", angelSymbol);
                angelBody.put("symboltoken", "");  // Will be resolved by Angel One if empty
                angelBody.put("transactiontype", side.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                angelBody.put("exchange", exchange);
                angelBody.put("ordertype", angelOrderType);
                angelBody.put("producttype", angelProd);
                angelBody.put("duration", "DAY");
                angelBody.put("price", isMarket ? "0" : String.valueOf(price));
                angelBody.put("squareoff", "0");
                angelBody.put("stoploss", "0");
                angelBody.put("quantity", String.valueOf(qty));
                angelBody.put("triggerprice", "0");
                return angelOneClient.placeOrder(apiKey, token, angelBody);
            }
            default:
                return Mono.error(new RuntimeException("Unsupported broker: " + account.getBrokerId()));
        }
    }

    private String extractOrderId(Map response) {
        if (response == null) return "unknown";
        // Different brokers return orderId in different fields
        for (String key : List.of("order_id", "orderId", "orderid", "uniqueorderid", "data", "id", "payload")) {
            Object val = response.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
            if (val instanceof Map m) {
                // Nested: data.order_id (Upstox, Zerodha), data.orderid (AngelOne)
                Object inner = m.get("order_id");
                if (inner == null) inner = m.get("orderId");
                if (inner == null) inner = m.get("orderid");
                if (inner == null) inner = m.get("uniqueorderid");
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
        r.put("supportedBrokers", List.of("GROWW", "ZERODHA", "FYERS", "UPSTOX", "DHAN", "ANGELONE"));
        r.put("modes", List.of("manual", "polling", "postback", "websocket"));
        r.put("detectionMethod", Map.of(
                "ZERODHA", "postback (~100ms)",
                "FYERS", "websocket (~50ms)",
                "UPSTOX", "websocket (~50ms)",
                "DHAN", "polling (1s)",
                "GROWW", "polling (1s)",
                "ANGELONE", "polling (1s)"
        ));
        return Mono.just(r);
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
