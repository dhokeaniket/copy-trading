package com.copytrading.engine;

import com.copytrading.alert.BalanceAlertService;
import com.copytrading.config.EnginePollingProperties;
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
import com.copytrading.broker.groww.GrowwProxyRouter;
import com.copytrading.broker.proxy.ProxyHttpClient;
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
import org.springframework.web.reactive.function.client.WebClient;
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

    // Cancel propagation dedup: masterId:masterOrderId → timestamp (a CANCELLED master order is seen on
    // every poll cycle, so we must only propagate the cancellation to children once).
    private final ConcurrentHashMap<String, Long> cancelledMasterOrders = new ConcurrentHashMap<>();

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
    private final GrowwProxyRouter growwProxyRouter;
    private final ProxyHttpClient proxyHttpClient;
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
    private final EnginePollingProperties pollingProperties;
    private final BrokerRateLimiter rateLimiter;
    private final SubscriptionCache subscriptionCache;

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
                             GrowwProxyRouter growwProxyRouter,
                             ProxyHttpClient proxyHttpClient,
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
                             BrokerCredentials credentials,
                             EnginePollingProperties pollingProperties,
                             BrokerRateLimiter rateLimiter,
                             SubscriptionCache subscriptionCache) {
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
        this.growwProxyRouter = growwProxyRouter;
        this.proxyHttpClient = proxyHttpClient;
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
        this.pollingProperties = pollingProperties;
        this.rateLimiter = rateLimiter;
        this.subscriptionCache = subscriptionCache;
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

        // Duplicate signal detection.
        // Prefer the master broker's native order id (order_id / exchange_order_id) as the dedup basis:
        // the legacy HHmm-precision key collapsed two distinct master orders on the same symbol+qty
        // within the same minute into one, silently dropping the second. Fall back to the time-based
        // key only for manual copies that carry no master order id.
        boolean hasMasterOrderId = req.getMasterOrderId() != null && !req.getMasterOrderId().isBlank();
        String dedupBasis = hasMasterOrderId ? req.getMasterOrderId() : orderKey;
        String dedupKey = masterId + ":" + req.getSide() + ":" + dedupBasis;
        Long lastSeen = recentOrderKeys.get(dedupKey);
        if (lastSeen != null && (System.currentTimeMillis() - lastSeen) < 60_000) {
            log.warn("COPY_DUPLICATE_SIGNAL master={} symbol={} side={} dedupKey={} — ignoring duplicate within 60s",
                    masterId, req.getSymbol(), req.getSide(), dedupBasis);
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
        String market = marketLabel(preparedReq.getSymbol());
        log.info("COPY_ENGINE_START master={} symbol={} market={} qty={} side={} product={} masterBroker={} orderKey={} copyGroupId={}",
                masterId, preparedReq.getSymbol(), market, preparedReq.getQty(), preparedReq.getSide(), preparedReq.getProduct(),
                preparedReq.getMasterBrokerId(), orderKey, copyGroupId);

        return subscriptionCache.getActiveSubscriptions(masterId)
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

    /** Uses masterBrokerId on the request, else the master's active account (same as before — no random broker pick). */
    private Mono<CopyTradeRequest> resolveMasterBroker(UUID masterId, CopyTradeRequest req) {
        if (req.getMasterBrokerId() != null && !req.getMasterBrokerId().isBlank()) {
            return Mono.just(req);
        }
        return activeAccountRepo.findByMasterId(masterId)
                .next()
                .flatMap(active -> brokerRepo.findById(active.getBrokerAccountId()))
                .doOnNext(acc -> {
                    req.setMasterBrokerId(acc.getBrokerId());
                    log.info("COPY_MASTER_BROKER_RESOLVED master={} broker={} accountId={}",
                            masterId, acc.getBrokerId(), acc.getId());
                })
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("COPY_MASTER_BROKER_UNKNOWN master={} — set active master account; will infer symbol format from symbol",
                                masterId)))
                .thenReturn(req);
    }

    /**
     * Replicate a single trade to one child.
     */
    private Mono<Map<String, Object>> replicateToChild(UUID masterId, Subscription sub, CopyTradeRequest req,
                                                        String copyGroupId, Instant engineReceivedAt) {
        UUID childId = sub.getChildId();
        UUID brokerAccountId = sub.getBrokerAccountId();
        double scale = sub.getScalingFactor();
        // Use Money.scaleQty for precise rounding (no float truncation)
        int rawScaled = Money.scaleQty(req.getQty(), scale);
        int scaledQty = lotScaler.apply(rawScaled, req.getSymbol());

        if (scaledQty < 1) {
            String reason = symbolMapper.isDerivative(req.getSymbol()) && rawScaled > 0 ? "SUB_LOT_SIZE" : "ZERO_QUANTITY";
            String msg = "SUB_LOT_SIZE".equals(reason)
                    ? "Scaled qty " + rawScaled + " is below one F&O lot for " + req.getSymbol()
                    : "Scaled quantity is 0 (scale=" + scale + " × qty=" + req.getQty() + " = 0). Order not placed.";
            log.info("COPY_SKIP child={} reason={} symbol={} market={} scale={} masterQty={} childQty={}",
                    childId, reason, req.getSymbol(), marketLabel(req.getSymbol()), scale, req.getQty(), scaledQty);
            return logAndReturn(masterId, childId, req, "SKIPPED", msg, null, reason, null, copyGroupId, engineReceivedAt, scaledQty);
        }

        // Generate unique order key: SHA256(INSTRUMENT+QTY+YYYYMMDD+HHmm)
        String orderKey = generateOrderKey(req.getSymbol(), req.getQty());

        return riskService.checkRiskLimits(childId, brokerAccountId)
                .flatMap(riskResult -> {
                    if (!riskResult.isEmpty()) {
                        log.info("COPY_SKIP child={} reason=RISK_LIMIT symbol={} market={} detail={}",
                                childId, req.getSymbol(), marketLabel(req.getSymbol()), riskResult);
                        return logAndReturn(masterId, childId, req, "SKIPPED",
                                "Risk limit: " + riskResult, null, "RISK_LIMIT", null, copyGroupId, engineReceivedAt, scaledQty);
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
                                                log.info("COPY_SKIP child={} reason={} symbol={} market={}",
                                                        childId, skip, req.getSymbol(), marketLabel(req.getSymbol()));
                                                return logAndReturn(masterId, childId, req, "SKIPPED", blockReason, null, skip,
                                                        null, copyGroupId, engineReceivedAt, scaledQty);
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
                                .then(placeOrderOnChild(masterId, childId, account, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt, sub));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        logAndReturn(masterId, childId, req, "FAILED",
                                "No broker account linked. Child needs to link a broker account and re-subscribe.",
                                null, null, null, copyGroupId, engineReceivedAt, scaledQty)
                    ));
        }

        return brokerRepo.findById(brokerAccountId)
                .switchIfEmpty(Mono.defer(() ->
                        logAndReturn(masterId, childId, req, "FAILED", "Broker account not found",
                                null, null, null, copyGroupId, engineReceivedAt, scaledQty)
                                .then(Mono.empty())))
                .flatMap(account -> {
                    if (!account.isSessionActive() || account.getAccessToken() == null) {
                        return logAndReturn(masterId, childId, req, "FAILED",
                                "Broker session inactive. Child needs to re-login.",
                                account.getBrokerId(), null, null, copyGroupId, engineReceivedAt, scaledQty);
                    }
                    return placeOrderOnChild(masterId, childId, account, req, scaledQty, scale, orderKey, copyGroupId, engineReceivedAt, sub);
                });
    }

    /**
     * Place order on child's broker and handle success/failure logging.
     */
    private Mono<Map<String, Object>> placeOrderOnChild(UUID masterId, UUID childId,
                                                         BrokerAccount account, CopyTradeRequest req,
                                                         int scaledQty, double scale, String orderKey,
                                                         String copyGroupId, Instant engineReceivedAt,
                                                         Subscription sub) {
        boolean isFnO = symbolMapper.isFnO(req.getSymbol());
        if (BrokerProductMapper.shouldSkipIntradayCopy(req.getProduct(), isFnO)) {
            String msg = BrokerProductMapper.marketClosedMessage();
            log.info("COPY_SKIP child={} reason=MARKET_CLOSED symbol={} market={} product={}",
                    childId, req.getSymbol(), marketLabel(req.getSymbol()), req.getProduct());
            notifications.push(childId, "Copy trade skipped", msg, "MARKET_CLOSED").subscribe();
            notifications.push(masterId, "Copy trade skipped",
                    msg + " (" + req.getSide() + " " + req.getSymbol() + " for child)", "MARKET_CLOSED").subscribe();
            return logAndReturn(masterId, childId, req, "SKIPPED", msg, account.getBrokerId(), "MARKET_CLOSED", null, copyGroupId, engineReceivedAt, scaledQty);
        }
        UUID brokerAccountId = account.getId();
        long startMs = System.currentTimeMillis();

        // Apply price tolerance: for LIMIT/SL orders, adjust price by child's tolerance %
        double adjustedPrice = req.getPrice();
        double adjustedTrigger = req.getTriggerPrice();
        double tolerancePct = sub != null ? sub.getPriceTolerancePct() : 2.0;
        if (tolerancePct > 0 && adjustedPrice > 0) {
            String ot = req.getOrderType() != null ? req.getOrderType().toUpperCase() : "MARKET";
            if ("LIMIT".equals(ot) || "SL".equals(ot) || "SL-M".equals(ot)) {
                double factor = tolerancePct / 100.0;
                if ("BUY".equalsIgnoreCase(req.getSide())) {
                    adjustedPrice = Math.round(adjustedPrice * (1 + factor) * 100.0) / 100.0;
                    if (adjustedTrigger > 0) adjustedTrigger = Math.round(adjustedTrigger * (1 + factor) * 100.0) / 100.0;
                } else {
                    adjustedPrice = Math.round(adjustedPrice * (1 - factor) * 100.0) / 100.0;
                    if (adjustedTrigger > 0) adjustedTrigger = Math.round(adjustedTrigger * (1 - factor) * 100.0) / 100.0;
                }
            }
        }
        final double childPrice = adjustedPrice;
        final double childTrigger = adjustedTrigger;

        // Respect each broker's per-sec/per-min order caps before dispatching the child order.
        return rateLimiter.acquire(account.getBrokerId(), account.getId())
                .then(Mono.defer(() -> placeOrderOnBroker(account, req.getSymbol(), scaledQty,
                        req.getSide(), req.getProduct(), req.getOrderType(), childPrice, childTrigger, req.getExchange(),
                        req.getMasterBrokerId())))
                .flatMap(response -> {
                    long latencyMs = System.currentTimeMillis() - startMs;
                    String orderId = extractOrderId(response);
                    log.info("COPY_ORDER_PLACED child={} broker={} orderId={} symbol={} market={} qty={} latencyMs={}",
                            childId, account.getBrokerId(), orderId, req.getSymbol(), marketLabel(req.getSymbol()), scaledQty, latencyMs);

                    // Save child's trade to trades table
                    Trade childTrade = new Trade();
                    childTrade.setUserId(childId);
                    childTrade.setBrokerAccountId(brokerAccountId);
                    childTrade.setBrokerOrderId(orderId.length() > 100 ? orderId.substring(0, 100) : orderId);
                    childTrade.setInstrument(req.getSymbol());
                    // Use the instrument classifier (options AND futures), not a CE/PE-only regex,
                    // so futures like NIFTY26MAYFUT are recorded as FNO, not EQUITY.
                    boolean childFnO = symbolMapper.isDerivative(req.getSymbol());
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
                            "Order placed: " + orderId, account.getBrokerId(), null, latencyMs, copyGroupId, engineReceivedAt, scaledQty,
                            orderId)
                            .map(r -> { r.put("latencyMs", latencyMs); r.put("orderKey", orderKey); r.put("childPlacedAt", Instant.now().toString()); return r; });
                })
                .onErrorResume(e -> {
                    long latencyMs = System.currentTimeMillis() - startMs;
                    log.error("COPY_ORDER_FAILED child={} broker={} symbol={} market={} masterQty={} childQty={} error={} latencyMs={}",
                            childId, account.getBrokerId(), req.getSymbol(), marketLabel(req.getSymbol()),
                            req.getQty(), scaledQty, e.getMessage(), latencyMs);

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
                            "Order failed: " + e.getMessage(), account.getBrokerId(), null, latencyMs, copyGroupId, engineReceivedAt, scaledQty)
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
        String sym = symbolMapper.translate(symbol, masterBrokerId, account.getBrokerId());
        String token = credentials.accessToken(account);
        String prod = BrokerProductMapper.normalizeProduct(product);
        String oType = orderType != null ? orderType : "MARKET";
        boolean isMarket = oType.equalsIgnoreCase("MARKET");
        boolean isSL = oType.equalsIgnoreCase("SL") || oType.equalsIgnoreCase("SL-M");
        // Check isFnO on ORIGINAL symbol (before translation) since translated format may differ
        boolean isFnO = symbolMapper.isFnO(symbol);
        String exch = exchange != null ? exchange.toUpperCase() : "NSE";

        switch (account.getBrokerId()) {
            case "GROWW": {
                // POST /v1/order/create — JSON body, Bearer token
                // Use exchange from master's order; fallback: detect SENSEX/BANKEX → BSE
                String growwExch = ("BSE".equals(exch)
                        || sym.toUpperCase().startsWith("SENSEX") || sym.toUpperCase().startsWith("BANKEX")) ? "BSE" : exch;
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("trading_symbol", sym);
                b.put("quantity", qty);
                b.put("price", isMarket ? 0 : price);
                b.put("trigger_price", isSL ? triggerPrice : 0);
                b.put(BrokerFieldTranslator.validityFieldName("GROWW"), BrokerFieldTranslator.validityValue("GROWW"));
                b.put("exchange", BrokerFieldTranslator.exchangeSegment(growwExch, "GROWW", isFnO));
                b.put("segment", isFnO ? "FNO" : "CASH");
                b.put("product", BrokerFieldTranslator.product(prod, "GROWW", isFnO));
                b.put("order_type", BrokerFieldTranslator.orderType(oType, "GROWW"));
                b.put("transaction_type", BrokerFieldTranslator.transactionType(side, "GROWW"));
                b.put("order_reference_id", "COPY-" + System.currentTimeMillis());
                log.info("GROWW_ORDER_REQ masterSymbol={} childSymbol={} qty={} segment={} masterBroker={} ipSlot={} hasUserProxy={}",
                        symbol, sym, qty, isFnO ? "FNO" : "CASH", masterBrokerId, account.getIpSlot(), account.hasProxy());
                // Per-user proxy takes priority over ip_slot-based routing
                reactor.netty.http.client.HttpClient proxyClient = proxyHttpClient.getHttpClient(account);
                if (proxyClient == null) {
                    proxyClient = growwProxyRouter.getProxiedClient(account.getIpSlot());
                }
                return growwClient.placeOrder(token, b, proxyClient);
            }
            case "ZERODHA": {
                // POST /orders/regular — form-urlencoded, token api_key:access_token
                // Per-user Zerodha: use account's own API key, fallback to platform
                String apiKey = (account.getApiKey() != null && !account.getApiKey().isBlank())
                        ? account.getApiKey() : platformConfig.getZerodha().getApiKey();
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("tradingsymbol", sym);
                b.put("exchange", BrokerFieldTranslator.exchangeSegment(exch, "ZERODHA", isFnO));
                b.put("transaction_type", BrokerFieldTranslator.transactionType(side, "ZERODHA"));
                b.put("order_type", BrokerFieldTranslator.orderType(oType, "ZERODHA"));
                b.put("quantity", qty);
                b.put("product", BrokerFieldTranslator.product(prod, "ZERODHA", isFnO));
                b.put(BrokerFieldTranslator.validityFieldName("ZERODHA"), BrokerFieldTranslator.validityValue("ZERODHA"));
                if (isSL) {
                    b.put("trigger_price", triggerPrice);
                    if (oType.equalsIgnoreCase("SL")) b.put("price", price);
                } else if (isMarket) {
                    b.put("market_protection", 5); // 5% protection band for market orders
                } else {
                    b.put("price", price);
                }
                // Route through per-user proxy if configured
                if (account.hasProxy()) {
                    WebClient proxiedClient = proxyHttpClient.getWebClient(account, "https://api.kite.trade");
                    StringBuilder form = new StringBuilder();
                    b.forEach((k, v) -> {
                        if (form.length() > 0) form.append("&");
                        form.append(k).append("=").append(v);
                    });
                    return proxiedClient.post()
                            .uri("/orders/regular")
                            .header("Authorization", "token " + apiKey + ":" + token)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Kite-Version", "3")
                            .bodyValue(form.toString())
                            .retrieve()
                            .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                                    .flatMap(e -> Mono.error(new RuntimeException("Zerodha order " + r.statusCode() + ": " + e))))
                            .bodyToMono(Map.class);
                }
                return zerodhaClient.placeOrder(apiKey, token, b);
            }
            case "FYERS": {
                // POST /orders/sync — JSON, Authorization: appId:accessToken
                // side: 1=BUY, -1=SELL; type: 1=LIMIT, 2=MARKET, 3=SL-M, 4=SL
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + token;
                String fyersSym;
                if (isFnO) {
                    if (sym.contains(":")) {
                        fyersSym = sym;
                    } else {
                        fyersSym = ("BSE".equals(exch) ? "BSE:" : "NSE:") + sym;
                    }
                } else {
                    fyersSym = ("BSE".equals(exch) ? "BSE:" : "NSE:") + sym + "-EQ";
                }
                String fyersProd = BrokerFieldTranslator.product(prod, "FYERS", isFnO);
                int fyersType = Integer.parseInt(BrokerFieldTranslator.orderType(oType, "FYERS"));
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("symbol", fyersSym);
                b.put("qty", qty);
                b.put("type", fyersType);
                b.put("side", Integer.parseInt(BrokerFieldTranslator.transactionType(side, "FYERS")));
                b.put("productType", fyersProd);
                b.put("limitPrice", isMarket ? 0 : price);
                b.put("stopPrice", isSL ? triggerPrice : 0);
                b.put(BrokerFieldTranslator.validityFieldName("FYERS"), BrokerFieldTranslator.validityValue("FYERS"));
                b.put("disclosedQty", 0);
                b.put("offlineOrder", true);
                b.put("stopLoss", 0);
                b.put("takeProfit", 0);
                // Route through per-user proxy if configured
                if (account.hasProxy()) {
                    WebClient proxiedClient = proxyHttpClient.getWebClient(account, "https://api-t1.fyers.in/api/v3");
                    return proxiedClient.post()
                            .uri("/orders/sync")
                            .header("Authorization", fyersAuth)
                            .header("Content-Type", "application/json")
                            .bodyValue(b)
                            .retrieve()
                            .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                                    .flatMap(e -> Mono.error(new RuntimeException("Fyers order " + r.statusCode() + ": " + e))))
                            .bodyToMono(Map.class);
                }
                return fyersClient.placeOrder(fyersAuth, b);
            }
            case "UPSTOX": {
                // POST /v2/order/place — JSON, Bearer token
                // instrument_token: "NSE_EQ|INE002A01018" (ISIN format from instrument master)
                // product: I=intraday, D=delivery
                String upProd = BrokerFieldTranslator.product(prod, "UPSTOX", isFnO);
                String upSym = instruments.getUpstoxInstrumentKey(sym, isFnO);
                if (upSym == null) {
                    // Fail safe: Upstox requires an ISIN-based instrument_token (e.g. NSE_EQ|INE002A01018).
                    // Never fall back to a raw trading symbol — Upstox rejects it and it can mis-route.
                    return Mono.error(new RuntimeException(
                            "Upstox instrument not found for symbol=" + sym
                                    + " (load instrument master or check F&O symbol mapping)"));
                }
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("quantity", qty);
                b.put("product", upProd);
                b.put(BrokerFieldTranslator.validityFieldName("UPSTOX"), BrokerFieldTranslator.validityValue("UPSTOX"));
                b.put("price", isMarket ? 0 : price);
                b.put("tag", "COPY" + System.currentTimeMillis());
                b.put("instrument_token", upSym);
                b.put("order_type", BrokerFieldTranslator.orderType(oType, "UPSTOX"));
                b.put("transaction_type", BrokerFieldTranslator.transactionType(side, "UPSTOX"));
                b.put("disclosed_quantity", 0);
                b.put("trigger_price", isSL ? triggerPrice : 0);
                b.put("is_amo", false);
                if (isMarket || oType.equalsIgnoreCase("SL-M")) {
                    b.put("market_protection", -1);
                }
                // Route through per-user proxy if configured
                if (account.hasProxy()) {
                    WebClient proxiedClient = proxyHttpClient.getWebClient(account, "https://api.upstox.com");
                    return proxiedClient.post()
                            .uri("/v2/order/place")
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .bodyValue(b)
                            .retrieve()
                            .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                                    .flatMap(e -> Mono.error(new RuntimeException("Upstox order " + r.statusCode() + ": " + e))))
                            .bodyToMono(Map.class);
                }
                return upstoxClient.placeOrder(token, b);
            }
            case "DHAN": {
                // POST /v2/orders — JSON, access-token header
                // REQUIRED: dhanClientId, securityId (numeric), exchangeSegment
                // securityId resolved from Dhan instrument CSV (loaded on startup)
                String dhanExch = BrokerFieldTranslator.exchangeSegment(exch, "DHAN", isFnO);
                String dhanProd = BrokerFieldTranslator.product(prod, "DHAN", isFnO);
                // Derive expiry date from master symbol for precise weekly/monthly lookup
                String expiryDate = symbolMapper.extractExpiryDate(symbol, masterBrokerId);
                String secId = instruments.getDhanSecurityId(sym, isFnO, expiryDate);
                if (secId == null && !sym.equalsIgnoreCase(symbol)) {
                    secId = instruments.getDhanSecurityId(symbol, isFnO, expiryDate);
                }
                String clientId = account.getClientId() != null && !account.getClientId().isBlank()
                        ? account.getClientId()
                        : account.getApiKey(); // Dhan: apiKey IS the clientId

                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("dhanClientId", clientId);
                b.put("transactionType", BrokerFieldTranslator.transactionType(side, "DHAN"));
                b.put("exchangeSegment", dhanExch);
                b.put("productType", dhanProd);
                b.put("orderType", BrokerFieldTranslator.orderType(oType, "DHAN"));
                b.put(BrokerFieldTranslator.validityFieldName("DHAN"), BrokerFieldTranslator.validityValue("DHAN"));
                b.put("quantity", qty);
                b.put("price", isMarket ? 0 : price);
                b.put("triggerPrice", isSL ? triggerPrice : 0);
                b.put("disclosedQuantity", 0);
                b.put("afterMarketOrder", false);
                b.put("correlationId", "COPY" + System.currentTimeMillis());

                if (secId != null) {
                    b.put("securityId", secId);
                    log.info("DHAN_ORDER_REQ masterSymbol={} childSymbol={} securityId={} qty={} exchangeSegment={} lotSize={} masterBroker={}",
                            symbol, sym, secId, qty, dhanExch, instruments.getLotSize(symbol), masterBrokerId);
                    // Route through per-user proxy if configured
                    if (account.hasProxy()) {
                        WebClient proxiedClient = proxyHttpClient.getWebClient(account, "https://api.dhan.co");
                        return proxiedClient.post()
                                .uri("/v2/orders")
                                .header("access-token", token)
                                .header("Content-Type", "application/json")
                                .bodyValue(b)
                                .retrieve()
                                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                                        .flatMap(e -> Mono.error(new RuntimeException("Dhan order " + r.statusCode() + ": " + e))))
                                .bodyToMono(Map.class);
                    }
                    return dhanClient.placeOrder(token, b);
                }
                return dhanClient.searchSecurityId(token, sym, dhanExch)
                        .flatMap(found -> {
                            if (found == null || found.isBlank()) {
                                return Mono.error(new RuntimeException(
                                        "Dhan securityId not found for " + sym + " (master=" + symbol + ")"));
                            }
                            b.put("securityId", found);
                            log.info("DHAN_ORDER_REQ masterSymbol={} childSymbol={} securityId={} qty={} (API search) masterBroker={}",
                                    symbol, sym, found, qty, masterBrokerId);
                            // Route through per-user proxy if configured
                            if (account.hasProxy()) {
                                WebClient proxiedClient = proxyHttpClient.getWebClient(account, "https://api.dhan.co");
                                return proxiedClient.post()
                                        .uri("/v2/orders")
                                        .header("access-token", token)
                                        .header("Content-Type", "application/json")
                                        .bodyValue(b)
                                        .retrieve()
                                        .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                                                .flatMap(e -> Mono.error(new RuntimeException("Dhan order " + r.statusCode() + ": " + e))))
                                        .bodyToMono(Map.class);
                            }
                            return dhanClient.placeOrder(token, b);
                        })
                        .switchIfEmpty(Mono.error(new RuntimeException(
                                "Dhan securityId not found for " + sym + " (master=" + symbol + ")")));
            }
            case "ANGELONE": {
                // POST /rest/secure/angelbroking/order/v1/placeOrder — JSON
                // producttype: INTRADAY, DELIVERY, CARRYFORWARD
                // symboltoken is REQUIRED (numeric token from scrip master)
                String apiKey = platformConfig.getAngelone().getApiKey();
                String angelProd = BrokerFieldTranslator.product(prod, "ANGELONE", isFnO);
                String angelSym = isFnO ? sym : sym + "-EQ";
                String angelToken = instruments.getAngelToken(angelSym, isFnO);
                if (angelToken == null) angelToken = instruments.getAngelToken(sym, isFnO);
                if (angelToken == null || angelToken.isBlank()) {
                    return Mono.error(new RuntimeException(
                            "Angel One symboltoken not found for " + angelSym + " (master=" + symbol + ")"));
                }
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("variety", "NORMAL");
                b.put("tradingsymbol", angelSym);
                b.put("symboltoken", angelToken);
                b.put("transactiontype", BrokerFieldTranslator.transactionType(side, "ANGELONE"));
                // Honour the actual exchange: BSE equity -> BSE, BSE F&O -> BFO (was hardcoded NSE/NFO)
                b.put("exchange", BrokerFieldTranslator.exchangeSegment(exch, "ANGELONE", isFnO));
                b.put("ordertype", BrokerFieldTranslator.orderType(oType, "ANGELONE"));
                b.put("producttype", angelProd);
                b.put(BrokerFieldTranslator.validityFieldName("ANGELONE"), BrokerFieldTranslator.validityValue("ANGELONE"));
                b.put("price", isMarket ? "0" : String.valueOf(price));
                b.put("squareoff", "0");
                b.put("stoploss", "0");
                b.put("quantity", String.valueOf(qty));
                b.put("triggerprice", isSL ? String.valueOf(triggerPrice) : "0");
                // Route through per-user proxy if configured
                if (account.hasProxy()) {
                    WebClient proxiedClient = proxyHttpClient.getWebClient(account, "https://apiconnect.angelone.in");
                    return proxiedClient.post()
                            .uri("/rest/secure/angelbroking/order/v1/placeOrder")
                            .header("Authorization", "Bearer " + token)
                            .header("X-PrivateKey", apiKey)
                            .header("X-ClientLocalIP", "127.0.0.1")
                            .header("X-ClientPublicIP", "127.0.0.1")
                            .header("X-MACAddress", "00:00:00:00:00:00")
                            .header("X-UserType", "USER")
                            .header("X-SourceID", "WEB")
                            .header("Content-Type", "application/json")
                            .bodyValue(b)
                            .retrieve()
                            .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                                    .flatMap(e -> Mono.error(new RuntimeException("AngelOne placeOrder " + r.statusCode() + ": " + e))))
                            .bodyToMono(Map.class);
                }
                return angelOneClient.placeOrder(apiKey, token, b);
            }
            default:
                return Mono.error(new RuntimeException("Unsupported broker: " + account.getBrokerId()));
        }
    }

    private String extractOrderId(Map response) {
        return OrderIdExtractor.extract(response, null).brokerOrderId();
    }

    /** Extract both broker and exchange order IDs for a specific broker. */
    private OrderIdExtractor.OrderIds extractOrderIds(Map response, String brokerId) {
        return OrderIdExtractor.extract(response, brokerId);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker) {
        return logAndReturn(masterId, childId, req, status, message, broker, null, null, null, null, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason) {
        return logAndReturn(masterId, childId, req, status, message, broker, skipReason, null, null, null, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason, Long latencyMs) {
        return logAndReturn(masterId, childId, req, status, message, broker, skipReason, latencyMs, null, null, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason,
                                                    Long latencyMs, String copyGroupId, Instant engineReceivedAt) {
        return logAndReturn(masterId, childId, req, status, message, broker, skipReason, latencyMs, copyGroupId, engineReceivedAt, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason,
                                                    Long latencyMs, String copyGroupId, Instant engineReceivedAt, Integer childQty) {
        return logAndReturn(masterId, childId, req, status, message, broker, skipReason, latencyMs, copyGroupId,
                engineReceivedAt, childQty, null);
    }

    private Mono<Map<String, Object>> logAndReturn(UUID masterId, UUID childId, CopyTradeRequest req,
                                                    String status, String message, String broker, String skipReason,
                                                    Long latencyMs, String copyGroupId, Instant engineReceivedAt,
                                                    Integer childQty, String childBrokerOrderId) {
        Instant childPlacedAt = "SUCCESS".equals(status) ? Instant.now() : null;

        // Save to copy_logs
        CopyLog cl = new CopyLog();
        cl.setMasterId(masterId);
        cl.setChildId(childId);
        // Link this child copy back to the master order so cancel propagation / reconciliation can find it.
        cl.setMasterTradeId(req.getMasterOrderId());
        cl.setSymbol(req.getSymbol());
        cl.setQty(req.getQty());                 // master qty
        cl.setChildQty(childQty);                // child scaled qty (was always logged as master qty before)
        cl.setChildBrokerOrderId(childBrokerOrderId != null && childBrokerOrderId.length() > 100
                ? childBrokerOrderId.substring(0, 100) : childBrokerOrderId);
        cl.setTradeType(req.getSide());
        cl.setMasterStatus("EXECUTED");
        cl.setChildStatus(status);
        String logMessage = message;
        if (childQty != null && childQty > 0 && ("FAILED".equals(status) || "SKIPPED".equals(status))) {
            logMessage = message + " [childQty=" + childQty + "]";
        }
        cl.setErrorMessage("FAILED".equals(status) ? logMessage : null);
        if ("SKIPPED".equals(status) && skipReason != null) {
            cl.setSkipReason(skipReason);
        }
        cl.setLatencyMs(latencyMs);
        cl.setCopyGroupId(copyGroupId);
        cl.setEngineReceivedAt(engineReceivedAt);
        cl.setChildPlacedAt(childPlacedAt);
        cl.setProduct(req.getProduct());
        cl.setOrderType(req.getOrderType());
        cl.setPrice(req.getPrice() != 0 ? req.getPrice() : null);
        cl.setTriggerPrice(req.getTriggerPrice() != 0 ? req.getTriggerPrice() : null);
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
                    r.put("masterQty", req.getQty());
                    if (childQty != null) r.put("childQty", childQty);
                    r.put("masterBroker", req.getMasterBrokerId());
                    if (req.getMasterOrderId() != null) r.put("masterTradeId", req.getMasterOrderId());
                    if (childBrokerOrderId != null) r.put("childBrokerOrderId", childBrokerOrderId);
                    r.put("scaledQty", childQty != null ? childQty : req.getQty());
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
     * Propagate a master-order cancellation to all of its child copies.
     *
     * <p>Looks up every child copy linked to the master order (via {@code master_trade_id}) that was
     * placed successfully and still has a child broker order id, then cancels each via the broker API.
     * Idempotent within an hour so repeated polling of the same CANCELLED master order does not re-cancel.
     */
    public Mono<Long> propagateCancel(UUID masterId, String masterOrderId) {
        if (masterOrderId == null || masterOrderId.isBlank()) return Mono.just(0L);
        String key = masterId + ":" + masterOrderId;
        long now = System.currentTimeMillis();
        if (cancelledMasterOrders.putIfAbsent(key, now) != null) {
            return Mono.just(0L); // already propagated
        }
        cancelledMasterOrders.entrySet().removeIf(e -> (now - e.getValue()) > 3_600_000);
        log.info("CANCEL_PROPAGATE_START master={} masterOrderId={}", masterId, masterOrderId);

        return copyLogs.findByMasterTradeId(masterOrderId)
                .filter(cl -> "SUCCESS".equals(cl.getChildStatus())
                        && cl.getChildBrokerOrderId() != null && !cl.getChildBrokerOrderId().isBlank())
                .flatMap(cl -> subs.findByMasterIdAndChildId(masterId, cl.getChildId())
                        .filter(sub -> sub.getBrokerAccountId() != null)
                        .flatMap(sub -> brokerService.cancelOrder(sub.getBrokerAccountId(), cl.getChildId(), cl.getChildBrokerOrderId())
                                .doOnSuccess(r -> {
                                    log.info("CANCEL_PROPAGATE_OK master={} child={} childOrderId={}",
                                            masterId, cl.getChildId(), cl.getChildBrokerOrderId());
                                    notifications.push(cl.getChildId(), "Copy order cancelled",
                                            "Master cancelled " + cl.getSymbol() + " — your copied order was cancelled.",
                                            "ORDER_CANCELLED").subscribe();
                                })
                                .thenReturn(1L)
                                .onErrorResume(e -> {
                                    log.warn("CANCEL_PROPAGATE_FAIL master={} child={} childOrderId={} error={}",
                                            masterId, cl.getChildId(), cl.getChildBrokerOrderId(), e.getMessage());
                                    return Mono.just(0L);
                                })))
                .reduce(0L, Long::sum)
                .doOnNext(count -> log.info("CANCEL_PROPAGATE_DONE master={} masterOrderId={} cancelled={}",
                        masterId, masterOrderId, count));
    }

    /**
     * Get engine status — is polling active, how many masters being tracked, etc.
     */
    public Mono<Map<String, Object>> getStatus() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("engineStatus", "ACTIVE");
        r.put("pollingEnabled", false);
        long pollMs = pollingProperties.getIntervalMs();
        r.put("pollingIntervalMs", pollMs);
        r.put("pollingIntervalSeconds", pollMs / 1000.0);
        r.put("supportedBrokers", List.of("GROWW", "ZERODHA", "FYERS", "UPSTOX", "DHAN", "ANGELONE"));
        r.put("modes", List.of("manual", "polling", "postback", "websocket"));
        String pollLabel = "polling (~" + pollMs + "ms)";
        r.put("detectionMethod", Map.of(
                "ZERODHA", "postback (~100ms)",
                "FYERS", pollLabel,
                "UPSTOX", pollLabel,
                "DHAN", pollLabel,
                "GROWW", pollLabel,
                "ANGELONE", pollLabel
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

    private String marketLabel(String symbol) {
        return symbolMapper.isFnO(symbol) ? "F&O" : "EQUITY";
    }
}
