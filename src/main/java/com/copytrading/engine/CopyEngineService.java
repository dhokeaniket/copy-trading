package com.copytrading.engine;

import com.copytrading.alert.BalanceAlertService;
import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.security.BrokerCredentials;
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
import com.copytrading.notification.TelegramService;
import com.copytrading.risk.RiskService;
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
import java.util.concurrent.ConcurrentHashMap;

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

    // Duplicate signal detection: orderKey → timestamp (prevents same signal processed twice within 60s)
    private final ConcurrentHashMap<String, Long> recentOrderKeys = new ConcurrentHashMap<>();

    private final SubscriptionRepository subs;
    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;
    private final CopyLogRepository copyLogs;
    private final NotificationService notifications;
    private final TelegramService telegram;
    private final RiskService riskService;
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
    private final InstrumentCache instruments;
    private final MasterActiveAccountRepository activeAccountRepo;
    private final SellGuardService sellGuard;
    private final LotSizeScaler lotScaler;
    private final BrokerCredentials credentials;

    public CopyEngineService(SubscriptionRepository subs,
                             BrokerAccountRepository brokerRepo,
                             BrokerAccountService brokerService,
                             CopyLogRepository copyLogs,
                             NotificationService notifications,
                             TelegramService telegram,
                             RiskService riskService,
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
                             PlatformBrokerConfig platformConfig,
                             InstrumentCache instruments,
                             MasterActiveAccountRepository activeAccountRepo,
                             SellGuardService sellGuard,
                             LotSizeScaler lotScaler,
                             BrokerCredentials credentials) {
        this.subs = subs;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.copyLogs = copyLogs;
        this.notifications = notifications;
        this.telegram = telegram;
        this.riskService = riskService;
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
        this.instruments = instruments;
        this.activeAccountRepo = activeAccountRepo;
        this.sellGuard = sellGuard;
        this.lotScaler = lotScaler;
        this.credentials = credentials;
    }

    /**
     * Copy a trade from master to all active children.
     * Called by manual trigger or polling engine.
     */
    public Mono<Map<String, Object>> copyTrade(UUID masterId, CopyTradeRequest req) {
        long engineStartMs = System.currentTimeMillis();
        Instant engineReceivedAt = Instant.now();
        String copyGroupId = UUID.randomUUID().toString();
        String masterTriggeredAt = engineReceivedAt.toString();
        String orderKey = generateOrderKey(req.getSymbol(), req.getQty());

        // CT-039/CT-040/CT-041: Duplicate signal detection
        // Same master + same orderKey within 60 seconds = duplicate, reject it
        String dedupKey = masterId + ":" + req.getSide() + ":" + orderKey;
        Long lastSeen = recentOrderKeys.get(dedupKey);
        if (lastSeen != null && (System.currentTimeMillis() - lastSeen) < 60_000) {
            log.warn("COPY_DUPLICATE_SIGNAL master={} symbol={} side={} orderKey={} — ignoring duplicate within 60s",
                    masterId, req.getSymbol(), req.getSide(), orderKey);
            return Mono.just(Map.<String, Object>of(
                    "message", "Duplicate signal detected — same trade already processed within 60 seconds",
                    "duplicate", true,
                    "orderKey", orderKey));
        }
        recentOrderKeys.put(dedupKey, System.currentTimeMillis());
        // Cleanup old entries (older than 2 minutes) to prevent memory leak
        recentOrderKeys.entrySet().removeIf(e -> (System.currentTimeMillis() - e.getValue()) > 120_000);

        req.setProduct(BrokerProductMapper.normalizeProduct(req.getProduct()));

        Mono<CopyTradeRequest> prepared = resolveMasterBroker(masterId, req);

        return prepared.flatMap(preparedReq -> {
        log.info("COPY_ENGINE_START master={} symbol={} qty={} side={} product={} masterBroker={} orderKey={} copyGroupId={}",
                masterId, preparedReq.getSymbol(), preparedReq.getQty(), preparedReq.getSide(), preparedReq.getProduct(),
                preparedReq.getMasterBrokerId(), orderKey, copyGroupId);

        return subs.findByMasterIdAndCopyingStatus(masterId, "ACTIVE")
                .collectList()
                .flatMap(children -> {
                    if (children.isEmpty()) {
                        return Mono.just(Map.<String, Object>of(
                                "message", "No active children to copy to",
                                "childrenCount", 0));
                    }
                    return Flux.fromIterable(children)
                            .flatMap(sub -> replicateToChild(masterId, sub, preparedReq, copyGroupId, engineReceivedAt))
                            .collectList()
                            .flatMap(results -> {
                                long success = results.stream().filter(r -> "SUCCESS".equals(r.get("status"))).count();
                                long failed = results.stream().filter(r -> "FAILED".equals(r.get("status"))).count();
                                long skipped = results.stream().filter(r -> "SKIPPED".equals(r.get("status"))).count();
                                long totalLatencyMs = System.currentTimeMillis() - engineStartMs;

                                // Notify master
                                String masterMsg = "Trade " + preparedReq.getSide() + " " + preparedReq.getSymbol() +
                                        " ×" + preparedReq.getQty() + " copied to " + success + "/" + children.size() + " children";
                                notifications.push(masterId, "Trade Copied", masterMsg, "TRADE_COPIED")
                                        .subscribe();
                                // Telegram to master
                                telegram.sendToUser(masterId,
                                        "✅ <b>Copy Complete</b>\n" + preparedReq.getSide() + " " + preparedReq.getSymbol() +
                                        " ×" + preparedReq.getQty() + "\n📊 " + success + "/" + children.size() +
                                        " children | ⏱ " + totalLatencyMs + "ms")
                                        .subscribe();

                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("message", "Trade copy completed");
                                r.put("copyGroupId", copyGroupId);
                                r.put("symbol", preparedReq.getSymbol());
                                r.put("exchange", preparedReq.getExchange() != null ? preparedReq.getExchange() : "NSE");
                                r.put("segment", symbolMapper.isFnO(preparedReq.getSymbol()) ? "FNO" : "EQUITY");
                                r.put("side", preparedReq.getSide());
                                r.put("product", preparedReq.getProduct());
                                r.put("orderType", preparedReq.getOrderType());
                                r.put("masterQty", preparedReq.getQty());
                                r.put("childrenTotal", children.size());
                                r.put("success", success);
                                r.put("failed", failed);
                                r.put("skipped", skipped);
                                // Timing info
                                r.put("orderKey", orderKey);
                                r.put("masterTriggeredAt", masterTriggeredAt);
                                r.put("engineReceivedAt", engineReceivedAt.toString());
                                r.put("completedAt", Instant.now().toString());
                                r.put("totalExecutionMs", totalLatencyMs);
                                r.put("results", results);
                                return Mono.just(r);
                            });
                });
        });
    }

    private Mono<CopyTradeRequest> resolveMasterBroker(UUID masterId, CopyTradeRequest req) {
        if (req.getMasterBrokerId() != null && !req.getMasterBrokerId().isBlank()) {
            return Mono.just(req);
        }
        return activeAccountRepo.findById(masterId)
                .flatMap(active -> brokerRepo.findById(active.getBrokerAccountId()))
                .doOnNext(acc -> req.setMasterBrokerId(acc.getBrokerId()))
                .thenReturn(req)
                .defaultIfEmpty(req);
    }

    /**
     * Replicate a single trade to one child.
     */
    private Mono<Map<String, Object>> replicateToChild(UUID masterId, Subscription sub, CopyTradeRequest req,
                                                        String copyGroupId, Instant engineReceivedAt) {
        UUID childId = sub.getChildId();
        UUID brokerAccountId = sub.getBrokerAccountId();
        double scale = sub.getScalingFactor();
        int rawScaled = (int) (req.getQty() * scale);
        int scaledQty = lotScaler.apply(rawScaled, req.getSymbol());

        if (scaledQty < 1) {
            String reason = symbolMapper.isDerivative(req.getSymbol()) && rawScaled > 0 ? "SUB_LOT_SIZE" : "ZERO_QUANTITY";
            String msg = "SUB_LOT_SIZE".equals(reason)
                    ? "Scaled qty " + rawScaled + " is below one F&O lot for " + req.getSymbol()
                    : "Scaled quantity is 0 (scale=" + scale + " × qty=" + req.getQty() + " = 0). Order not placed.";
            log.info("COPY_SKIP child={} reason={} scale={} masterQty={}", childId, reason, scale, req.getQty());
            return logAndReturn(masterId, childId, req, "SKIPPED", msg, null, reason, null, copyGroupId, engineReceivedAt);
        }

        // Generate unique order key: SHA256(INSTRUMENT+QTY+YYYYMMDD+HHmm)
        String orderKey = generateOrderKey(req.getSymbol(), req.getQty());

        return riskService.checkRiskLimits(childId, brokerAccountId)
                .flatMap(riskResult -> {
                    if (!riskResult.isEmpty()) {
                        log.info("COPY_SKIP child={} reason=RISK_LIMIT detail={}", childId, riskResult);
                        return logAndReturn(masterId, childId, req, "SKIPPED",
                                "Risk limit: " + riskResult, null, "RISK_LIMIT", null, copyGroupId, engineReceivedAt);
                    }

                    if ("SELL".equalsIgnoreCase(req.getSide())) {
                        return brokerRepo.findById(brokerAccountId)
                                .flatMap(childAccount -> sellGuard.checkSellAllowed(
                                        masterId, sub, req.getSymbol(), scaledQty, brokerAccountId,
                                        childAccount.getBrokerId(), req.getMasterBrokerId())
                                        .flatMap(blockReason -> {
                                            if (blockReason != null && !blockReason.isBlank()) {
                                                String skip = blockReason.startsWith("NO_POSITION") ? "NO_POSITION"
                                                        : blockReason.startsWith("INSUFFICIENT_POSITION") ? "INSUFFICIENT_POSITION"
                                                        : "SELL_BLOCKED";
                                                log.info("COPY_SKIP child={} reason={} symbol={}", childId, skip, req.getSymbol());
                                                return logAndReturn(masterId, childId, req, "SKIPPED", blockReason, null, skip,
                                                        null, copyGroupId, engineReceivedAt);
                                            }
                                            return proceedWithOrder(masterId, childId, brokerAccountId, sub, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt);
                                        }))
                                .switchIfEmpty(proceedWithOrder(masterId, childId, brokerAccountId, sub, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt));
                    }

                    return proceedWithOrder(masterId, childId, brokerAccountId, sub, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt);
                });
    }

    /** Proceed with order placement after all checks pass */
    private Mono<Map<String, Object>> proceedWithOrder(UUID masterId, UUID childId, UUID brokerAccountId,
                                                        Subscription sub, CopyTradeRequest req,
                                                        int scaledQty, double scale, String orderKey,
                                                        String copyGroupId, Instant engineReceivedAt) {

        if (brokerAccountId == null) {
            // Fallback: try to find child's first active broker account and update the subscription
            return brokerRepo.findByUserId(childId)
                    .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                    .next()
                    .flatMap(account -> {
                        // Auto-fix the subscription with the found broker account
                        sub.setBrokerAccountId(account.getId());
                        return subs.save(sub)
                                .then(placeOrderOnChild(masterId, childId, account, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        logAndReturn(masterId, childId, req, "FAILED",
                                "No broker account linked. Child needs to link a broker account and re-subscribe.", null, null, null, copyGroupId, engineReceivedAt)
                    ));
        }

        return brokerRepo.findById(brokerAccountId)
                .switchIfEmpty(Mono.defer(() ->
                        logAndReturn(masterId, childId, req, "FAILED", "Broker account not found", null, null, null, copyGroupId, engineReceivedAt)
                                .then(Mono.empty())))
                .flatMap(account -> {
                    if (!account.isSessionActive() || account.getAccessToken() == null) {
                        return logAndReturn(masterId, childId, req, "FAILED",
                                "Broker session inactive. Child needs to re-login.", account.getBrokerId(), null, null, copyGroupId, engineReceivedAt);
                    }
                    return placeOrderOnChild(masterId, childId, account, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt);
                });
    }

    /**
     * Place order on child's broker and handle success/failure logging.
     */
    private Mono<Map<String, Object>> placeOrderOnChild(UUID masterId, UUID childId,
                                                         BrokerAccount account, CopyTradeRequest req,
                                                         int scaledQty, double scale, String orderKey,
                                                         String copyGroupId, Instant engineReceivedAt) {
        boolean isFnO = symbolMapper.isFnO(req.getSymbol());
        if (BrokerProductMapper.shouldSkipIntradayCopy(req.getProduct(), isFnO)) {
            String msg = BrokerProductMapper.marketClosedMessage();
            log.info("COPY_SKIP child={} reason=MARKET_CLOSED symbol={} product={}", childId, req.getSymbol(), req.getProduct());
            notifications.push(childId, "Copy trade skipped", msg, "MARKET_CLOSED").subscribe();
            notifications.push(masterId, "Copy trade skipped",
                    msg + " (" + req.getSide() + " " + req.getSymbol() + " for child)", "MARKET_CLOSED").subscribe();
            return logAndReturn(masterId, childId, req, "SKIPPED", msg, account.getBrokerId(), "MARKET_CLOSED", null, copyGroupId, engineReceivedAt);
        }
        UUID brokerAccountId = account.getId();
        long startMs = System.currentTimeMillis();
        return placeOrderOnBroker(account, req.getSymbol(), scaledQty,
                req.getSide(), req.getProduct(), req.getOrderType(), req.getPrice(), req.getTriggerPrice(), req.getExchange(),
                req.getMasterBrokerId())
                .flatMap(response -> {
                    long latencyMs = System.currentTimeMillis() - startMs;
                    String orderId = extractOrderId(response);
                    log.info("COPY_ORDER_PLACED child={} broker={} orderId={} symbol={} qty={} latencyMs={}",
                            childId, account.getBrokerId(), orderId, req.getSymbol(), scaledQty, latencyMs);

                    // Save child's trade to trades table
                    Trade childTrade = new Trade();
                    childTrade.setUserId(childId);
                    childTrade.setBrokerAccountId(brokerAccountId);
                    childTrade.setBrokerOrderId(orderId.length() > 100 ? orderId.substring(0, 100) : orderId);
                    childTrade.setInstrument(req.getSymbol());
                    boolean childFnO = req.getSymbol() != null && req.getSymbol().matches(".*\\d+(CE|PE)$");
                    childTrade.setExchange(req.getExchange() != null ? req.getExchange() : "NSE");
                    childTrade.setSegment(childFnO ? "FNO" : "EQUITY");
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
                            "Order placed: " + orderId, account.getBrokerId(), null, latencyMs, copyGroupId, engineReceivedAt)
                            .map(r -> { r.put("latencyMs", latencyMs); r.put("orderKey", orderKey); r.put("childPlacedAt", Instant.now().toString()); return r; });
                })
                .onErrorResume(e -> {
                    long latencyMs = System.currentTimeMillis() - startMs;
                    log.error("COPY_ORDER_FAILED child={} broker={} error={} latencyMs={}",
                            childId, account.getBrokerId(), e.getMessage(), latencyMs);

                    // If 401/session expired or IP inactive, mark session inactive (only once, don't spam)
                    if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized") || e.getMessage().contains("GA005"))) {
                        // Mark session inactive in DB to prevent further attempts
                        account.setSessionActive(false);
                        brokerRepo.save(account).subscribe();
                        hub.publish("{\"event\":\"SESSION_EXPIRED\",\"data\":{\"childId\":\"" + childId + "\",\"broker\":\"" + account.getBrokerId() + "\",\"accountId\":\"" + brokerAccountId + "\"}}");
                        notifications.push(childId, "Broker session expired", "Your " + BrokerAccountDto.from(account).getBrokerName() + " session has expired. Re-login to resume copy trading.", "SESSION_EXPIRED").subscribe();
                    } else {
                        notifications.push(childId,
                                "⚠️ Trade Copy Failed",
                                "Failed to copy " + req.getSide() + " " + req.getSymbol() +
                                        ": " + e.getMessage(),
                                "TRADE_FAILED"
                        ).subscribe();
                    }

                    // Check balance async (might be the reason it failed)
                    balanceAlert.checkAndAlert(childId, brokerAccountId).subscribe();

                    return logAndReturn(masterId, childId, req, "FAILED",
                            "Order failed: " + e.getMessage(), account.getBrokerId(), null, latencyMs, copyGroupId, engineReceivedAt)
                            .map(r -> { r.put("latencyMs", latencyMs); r.put("orderKey", orderKey); return r; });
                });
    }

    /**
     * Place order on the child's broker using the real API client.
     * Each broker has its own specific order format — verified against official API docs.
     *
     * Zerodha: https://kite.trade/docs/connect/v3/orders/ (form-urlencoded)
     * Groww:   https://api.groww.in (JSON)
     * Fyers:   https://api-t1.fyers.in/api/v3 (JSON)
     * Upstox:  https://upstox.com/developer/api-documentation/place-order (JSON)
     * Dhan:    https://dhanhq.co/docs/v2/orders/ (JSON, requires numeric securityId)
     * Angel:   https://smartapi.angelone.in (JSON)
     */
    private Mono<Map> placeOrderOnBroker(BrokerAccount account, String symbol, int qty,
                                          String side, String product, String orderType, double price,
                                          double triggerPrice, String exchange, String masterBrokerId) {
        String sourceBroker = masterBrokerId != null && !masterBrokerId.isBlank() ? masterBrokerId : "GROWW";
        String sym = symbolMapper.translate(symbol, sourceBroker, account.getBrokerId());
        String token = credentials.accessToken(account);
        String prod = BrokerProductMapper.normalizeProduct(product);
        String oType = orderType != null ? orderType : "MARKET";
        boolean isMarket = oType.equalsIgnoreCase("MARKET");
        boolean isSL = oType.equalsIgnoreCase("SL") || oType.equalsIgnoreCase("SL-M");
        // Check isFnO on ORIGINAL symbol (before translation) since translated format may differ
        boolean isFnO = symbolMapper.isFnO(symbol);
        String txn = "BUY".equalsIgnoreCase(side) ? "BUY" : "SELL";
        String exch = exchange != null ? exchange.toUpperCase() : "NSE";

        switch (account.getBrokerId()) {
            case "GROWW": {
                // POST /v1/order/create — JSON body, Bearer token
                // Use exchange from master's order; fallback: detect SENSEX/BANKEX → BSE
                String growwExchange = "BSE".equals(exch) ? "BSE"
                        : (sym.toUpperCase().startsWith("SENSEX") || sym.toUpperCase().startsWith("BANKEX")) ? "BSE" : "NSE";
                String growwProd = isFnO ? (prod.equalsIgnoreCase("CNC") ? "NRML" : prod) : prod;
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("trading_symbol", sym);
                b.put("quantity", qty);
                b.put("price", isMarket ? 0 : price);
                b.put("trigger_price", isSL ? triggerPrice : 0);
                b.put("validity", "DAY");
                b.put("exchange", growwExchange);
                b.put("segment", isFnO ? "FNO" : "CASH");
                b.put("product", growwProd);
                b.put("order_type", isSL ? (oType.equalsIgnoreCase("SL-M") ? "SL-M" : "SL") : (isMarket ? "MARKET" : "LIMIT"));
                b.put("transaction_type", txn);
                b.put("order_reference_id", "COPY-" + System.currentTimeMillis());
                return growwClient.placeOrder(token, b);
            }
            case "ZERODHA": {
                // POST /orders/regular — form-urlencoded, token api_key:access_token
                String apiKey = platformConfig.getZerodha().getApiKey();
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("tradingsymbol", sym);
                b.put("exchange", isFnO ? "NFO" : ("BSE".equals(exch) ? "BSE" : "NSE"));
                b.put("transaction_type", txn);
                b.put("order_type", isSL ? (oType.equalsIgnoreCase("SL-M") ? "SL-M" : "SL") : (isMarket ? "MARKET" : "LIMIT"));
                b.put("quantity", qty);
                b.put("product", prod);
                b.put("validity", "DAY");
                if (isSL) {
                    b.put("trigger_price", triggerPrice);
                    if (oType.equalsIgnoreCase("SL")) b.put("price", price);
                } else if (isMarket) {
                    b.put("market_protection", 5); // 5% protection band for market orders
                } else {
                    b.put("price", price);
                }
                return zerodhaClient.placeOrder(apiKey, token, b);
            }
            case "FYERS": {
                // POST /orders/sync — JSON, Authorization: appId:accessToken
                // side: 1=BUY, -1=SELL; type: 1=LIMIT, 2=MARKET, 3=SL-M, 4=SL
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + token;
                String fyersSym = isFnO ? sym : "NSE:" + sym + "-EQ";
                String fyersProd = prod.equalsIgnoreCase("MIS") ? "INTRADAY"
                        : prod.equalsIgnoreCase("CNC") ? "CNC"
                        : prod.equalsIgnoreCase("NRML") ? "MARGIN" : "INTRADAY";
                int fyersType = isMarket ? 2 : isSL ? (oType.equalsIgnoreCase("SL-M") ? 3 : 4) : 1;
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("symbol", fyersSym);
                b.put("qty", qty);
                b.put("type", fyersType);
                b.put("side", "BUY".equalsIgnoreCase(side) ? 1 : -1);
                b.put("productType", fyersProd);
                b.put("limitPrice", isMarket ? 0 : price);
                b.put("stopPrice", isSL ? triggerPrice : 0);
                b.put("validity", "DAY");
                b.put("disclosedQty", 0);
                b.put("offlineOrder", false);
                b.put("stopLoss", 0);
                b.put("takeProfit", 0);
                return fyersClient.placeOrder(fyersAuth, b);
            }
            case "UPSTOX": {
                // POST /v2/order/place — JSON, Bearer token
                // instrument_token: "NSE_EQ|INE002A01018" (ISIN format from instrument master)
                // product: I=intraday, D=delivery
                String upProd = prod.equalsIgnoreCase("MIS") ? "I"
                        : prod.equalsIgnoreCase("CNC") ? "D"
                        : prod.equalsIgnoreCase("NRML") ? "D" : "I";
                String upSym = instruments.getUpstoxInstrumentKey(sym, isFnO);
                if (upSym == null) {
                    // Fallback: construct instrument key based on exchange
                    String upExch = "BSE".equals(exch) ? "BSE" : "NSE";
                    upSym = isFnO ? (upExch + "_FO|" + sym) : (upExch + "_EQ|" + sym);
                }
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("quantity", qty);
                b.put("product", upProd);
                b.put("validity", "DAY");
                b.put("price", isMarket ? 0 : price);
                b.put("tag", "COPY" + System.currentTimeMillis());
                b.put("instrument_token", upSym);
                b.put("order_type", isSL ? (oType.equalsIgnoreCase("SL-M") ? "SL-M" : "SL") : (isMarket ? "MARKET" : "LIMIT"));
                b.put("transaction_type", txn);
                b.put("disclosed_quantity", 0);
                b.put("trigger_price", isSL ? triggerPrice : 0);
                b.put("is_amo", false);
                return upstoxClient.placeOrder(token, b);
            }
            case "DHAN": {
                // POST /v2/orders — JSON, access-token header
                // REQUIRED: dhanClientId, securityId (numeric), exchangeSegment
                // securityId resolved from Dhan instrument CSV (loaded on startup)
                String dhanExch = isFnO ? ("BSE".equals(exch) ? "BSE_FNO" : "NSE_FNO") : ("BSE".equals(exch) ? "BSE_EQ" : "NSE_EQ");
                String dhanProd = BrokerProductMapper.toDhanProductType(prod, isFnO);
                String secId = instruments.getDhanSecurityId(sym, isFnO);
                String clientId = account.getClientId() != null ? account.getClientId() : "";

                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("dhanClientId", clientId);
                b.put("transactionType", txn);
                b.put("exchangeSegment", dhanExch);
                b.put("productType", dhanProd);
                b.put("orderType", isSL ? (oType.equalsIgnoreCase("SL-M") ? "SL-M" : "SL") : (isMarket ? "MARKET" : "LIMIT"));
                b.put("validity", "DAY");
                b.put("quantity", qty);
                b.put("price", isMarket ? 0 : price);
                b.put("triggerPrice", isSL ? triggerPrice : 0);
                b.put("disclosedQuantity", 0);
                b.put("afterMarketOrder", false);
                b.put("correlationId", "COPY" + System.currentTimeMillis());

                if (secId != null) {
                    b.put("securityId", secId);
                    return dhanClient.placeOrder(token, b);
                }
                // Fallback: search Dhan API
                return dhanClient.searchSecurityId(token, sym, dhanExch)
                        .flatMap(found -> {
                            b.put("securityId", (found != null && !found.isBlank()) ? found : sym);
                            return dhanClient.placeOrder(token, b);
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            b.put("securityId", sym);
                            return dhanClient.placeOrder(token, b);
                        }));
            }
            case "ANGELONE": {
                // POST /rest/secure/angelbroking/order/v1/placeOrder — JSON
                // producttype: INTRADAY, DELIVERY, CARRYFORWARD
                // symboltoken is REQUIRED (numeric token from scrip master)
                String apiKey = platformConfig.getAngelone().getApiKey();
                String angelProd = prod.equalsIgnoreCase("MIS") ? "INTRADAY"
                        : prod.equalsIgnoreCase("CNC") ? "DELIVERY"
                        : prod.equalsIgnoreCase("NRML") ? "CARRYFORWARD" : "INTRADAY";
                String angelSym = isFnO ? sym : sym + "-EQ";
                String angelToken = instruments.getAngelToken(angelSym, isFnO);
                if (angelToken == null) angelToken = instruments.getAngelToken(sym, isFnO);
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("variety", "NORMAL");
                b.put("tradingsymbol", angelSym);
                b.put("symboltoken", angelToken != null ? angelToken : "");
                b.put("transactiontype", txn);
                b.put("exchange", isFnO ? "NFO" : "NSE");
                b.put("ordertype", isSL ? (oType.equalsIgnoreCase("SL-M") ? "STOPLOSS_MARKET" : "STOPLOSS_LIMIT") : (isMarket ? "MARKET" : "LIMIT"));
                b.put("producttype", angelProd);
                b.put("duration", "DAY");
                b.put("price", isMarket ? "0" : String.valueOf(price));
                b.put("squareoff", "0");
                b.put("stoploss", "0");
                b.put("quantity", String.valueOf(qty));
                b.put("triggerprice", isSL ? String.valueOf(triggerPrice) : "0");
                return angelOneClient.placeOrder(apiKey, token, b);
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
        return logAndReturn(masterId, childId, req, status, message, broker, null, null, null, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason) {
        return logAndReturn(masterId, childId, req, status, message, broker, skipReason, null, null, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason, Long latencyMs) {
        return logAndReturn(masterId, childId, req, status, message, broker, skipReason, latencyMs, null, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason,
                                                    Long latencyMs, String copyGroupId, Instant engineReceivedAt) {
        Instant childPlacedAt = "SUCCESS".equals(status) ? Instant.now() : null;

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
        cl.setLatencyMs(latencyMs);
        cl.setCopyGroupId(copyGroupId);
        cl.setEngineReceivedAt(engineReceivedAt);
        cl.setChildPlacedAt(childPlacedAt);
        cl.setCreatedAt(Instant.now());

        // Send Telegram notification to child
        telegram.notifyTrade(childId, req.getSide(), req.getSymbol(), req.getQty(), status, message).subscribe();

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
                    r.put("copyGroupId", copyGroupId);
                    if (broker != null) r.put("broker", broker);
                    r.put("scaledQty", req.getQty());
                    r.put("engineReceivedAt", engineReceivedAt != null ? engineReceivedAt.toString() : null);
                    r.put("childPlacedAt", childPlacedAt != null ? childPlacedAt.toString() : null);
                    r.put("placedAt", Instant.now().toString());
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

    /**
     * Generate unique order key: SHA256 hash of INSTRUMENT+QTY+YYYYMMDD+HHmm
     * Format before hash: IOB5014052026T1314 (symbol + qty + date + time)
     * Returns first 16 chars of hex hash for brevity.
     */
    private static String generateOrderKey(String symbol, int qty) {
        try {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            String raw = symbol + qty + now.format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyyHHmm"));
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString(); // 16-char hex
        } catch (Exception e) {
            return "KEY" + System.currentTimeMillis();
        }
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
