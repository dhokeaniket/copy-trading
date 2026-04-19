package com.copytrading.engine;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.cache.PollingStateCache;
import com.copytrading.master.MasterActiveAccount;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.notification.NotificationService;
import com.copytrading.subscription.SubscriptionRepository;
import com.copytrading.trade.Trade;
import com.copytrading.trade.TradeRepository;
import com.copytrading.ws.TradeUpdatesHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls master's broker accounts for new orders every 10 seconds.
 * When a new order is detected, triggers CopyEngineService.copyTrade().
 *
 * Tracks last known order IDs per master to detect new ones.
 */
@Service
public class OrderPollingService {

    private static final Logger log = LoggerFactory.getLogger(OrderPollingService.class);

    private final MasterActiveAccountRepository activeAccountRepo;
    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;
    private final SubscriptionRepository subs;
    private final CopyEngineService copyEngine;
    private final TradeRepository tradeRepo;
    private final TradeUpdatesHub hub;
    private final PollingStateCache pollingCache;
    private final NotificationService notifications;

    // In-memory fallback for known orders (used alongside Redis)
    private final ConcurrentHashMap<UUID, Set<String>> knownOrders = new ConcurrentHashMap<>();

    // Toggle polling on/off
    private volatile boolean pollingEnabled = false;

    // Track last reset time
    private volatile Instant lastResetAt = Instant.now();

    public OrderPollingService(MasterActiveAccountRepository activeAccountRepo,
                               BrokerAccountRepository brokerRepo,
                               BrokerAccountService brokerService,
                               SubscriptionRepository subs,
                               CopyEngineService copyEngine,
                               TradeRepository tradeRepo,
                               TradeUpdatesHub hub,
                               PollingStateCache pollingCache,
                               NotificationService notifications) {
        this.activeAccountRepo = activeAccountRepo;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.subs = subs;
        this.copyEngine = copyEngine;
        this.tradeRepo = tradeRepo;
        this.hub = hub;
        this.pollingCache = pollingCache;
        this.notifications = notifications;
    }

    public boolean isPollingEnabled() { return pollingEnabled; }
    public void setPollingEnabled(boolean enabled) { this.pollingEnabled = enabled; }
    public Instant getLastResetAt() { return lastResetAt; }

    /**
     * Runs every 10 seconds. Checks each master's active broker account for new orders.
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 15000)
    public void pollMasterOrders() {
        if (!pollingEnabled) return;

        activeAccountRepo.findAll()
                .flatMap(activeAccount -> pollSingleMaster(activeAccount)
                        .onErrorResume(e -> {
                            log.warn("POLL_ERROR master={} error={}", activeAccount.getMasterId(), e.getMessage());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    private Mono<Void> pollSingleMaster(MasterActiveAccount active) {
        UUID masterId = active.getMasterId();
        UUID brokerAccountId = active.getBrokerAccountId();

        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .flatMap(account -> brokerService.getOrders(brokerAccountId, masterId)
                        .map(resp -> {
                            Object ordersObj = resp.get("orders");
                            List<?> ordersList = null;
                            if (ordersObj instanceof List<?> list) {
                                ordersList = list;
                            } else if (ordersObj instanceof Map<?,?> map) {
                                // Groww wraps in {order_list: [...]}
                                Object inner = map.get("order_list");
                                if (inner == null) inner = map.get("orderBook");
                                if (inner == null) inner = map.get("data");
                                if (inner instanceof List<?> list2) ordersList = list2;
                            }
                            if (ordersList != null && !ordersList.isEmpty()) {
                                return processOrders(masterId, account, ordersList);
                            }
                            return 0;
                        })
                        .onErrorResume(e -> {
                            log.debug("POLL_ORDERS_FAIL master={} error={}", masterId, e.getMessage());
                            return Mono.just(0);
                        }))
                .then();
    }

    @SuppressWarnings("unchecked")
    private int processOrders(UUID masterId, BrokerAccount account, List<?> ordersList) {
        Set<String> known = knownOrders.computeIfAbsent(masterId, k -> new HashSet<>());
        int newCount = 0;

        for (Object orderObj : ordersList) {
            if (!(orderObj instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) orderObj;

            String orderId = extractField(order, "order_id", "orderId", "id", "groww_order_id");
            String status = extractField(order, "status", "order_status");
            if (orderId == null || orderId.isBlank()) continue;

            // Only copy COMPLETE/EXECUTED orders that we haven't seen before
            if (known.contains(orderId)) continue;
            known.add(orderId);

            // Also mark in Redis (survives restarts)
            pollingCache.markOrderKnown(masterId, orderId).subscribe();

            if (!"COMPLETE".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)
                    && !"TRADED".equalsIgnoreCase(status)) {
                continue;
            }

            // Extract order details
            String symbol = extractField(order, "tradingsymbol", "trading_symbol", "symbol", "tradingSymbol");
            String side = extractField(order, "transaction_type", "side", "transactionType");
            String qtyStr = extractField(order, "quantity", "qty", "filled_quantity");
            String product = extractField(order, "product", "productType", "product_type");

            if (symbol == null || side == null || qtyStr == null) continue;

            int qty;
            try { qty = (int) Double.parseDouble(qtyStr); } catch (Exception e) { continue; }

            // Normalize side
            if ("1".equals(side)) side = "BUY";
            else if ("-1".equals(side)) side = "SELL";

            log.info("NEW_ORDER_DETECTED master={} broker={} orderId={} symbol={} side={} qty={}",
                    masterId, account.getBrokerId(), orderId, symbol, side, qty);

            // Save master's trade to DB (so master sees it in trade history)
            Trade masterTrade = new Trade();
            masterTrade.setUserId(masterId);
            masterTrade.setBrokerAccountId(account.getId());
            masterTrade.setBrokerOrderId(orderId);
            masterTrade.setInstrument(symbol);
            masterTrade.setExchange("NSE");
            masterTrade.setSegment("EQUITY");
            masterTrade.setOrderType("MARKET");
            masterTrade.setTransactionType(side.toUpperCase());
            masterTrade.setQuantity(qty);
            masterTrade.setPrice(0);
            masterTrade.setProduct(product != null ? product : "MIS");
            masterTrade.setStatus("EXECUTED");
            masterTrade.setPlacedAt(Instant.now());
            masterTrade.setExecutedAt(Instant.now());
            tradeRepo.save(masterTrade).subscribe(
                    saved -> log.info("MASTER_TRADE_SAVED id={} symbol={}", saved.getId(), symbol),
                    err -> log.warn("MASTER_TRADE_SAVE_FAIL: {}", err.getMessage())
            );

            // Publish WebSocket event
            hub.publish("{\"event\":\"TRADE_DETECTED\",\"masterId\":\"" + masterId +
                    "\",\"instrument\":\"" + symbol + "\",\"side\":\"" + side +
                    "\",\"qty\":" + qty + ",\"broker\":\"" + account.getBrokerId() + "\"}");

            // Trigger copy to all children
            CopyTradeRequest req = new CopyTradeRequest();
            req.setSymbol(symbol);
            req.setQty(qty);
            req.setSide(side.toUpperCase());
            req.setProduct(product != null ? product : "MIS");
            req.setOrderType("MARKET");
            req.setPrice(0);

            copyEngine.copyTrade(masterId, req)
                    .subscribe(
                            result -> log.info("POLL_COPY_DONE master={} symbol={} result={}",
                                    masterId, symbol, result.get("success") + "/" + result.get("childrenTotal")),
                            error -> log.error("POLL_COPY_ERROR master={} symbol={} error={}",
                                    masterId, symbol, error.getMessage())
                    );
            newCount++;
        }
        return newCount;
    }

    private String extractField(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) return val.toString();
        }
        return null;
    }

    /** Clear known orders for a master (e.g. at start of new trading day) */
    public void resetKnownOrders(UUID masterId) {
        knownOrders.remove(masterId);
        pollingCache.resetMaster(masterId).subscribe();
    }

    public void resetAllKnownOrders() {
        knownOrders.clear();
        pollingCache.resetAll().subscribe();
    }

    // Auto-reset polling cache at 9:15 AM IST (3:45 AM UTC) on weekdays
    @Scheduled(cron = "0 45 3 * * MON-FRI")
    public void autoResetPollingCache() {
        log.info("AUTO_RESET_POLLING: Clearing known orders cache at market open");
        knownOrders.clear();
        pollingCache.resetAll().subscribe();
        lastResetAt = Instant.now();
    }

    // Daily session reminder at 9:00 AM IST (3:30 AM UTC) on weekdays
    @Scheduled(cron = "0 30 3 * * MON-FRI")
    public void dailySessionReminder() {
        log.info("SESSION_REMINDER: Checking expired sessions");
        subs.findAll()
            .filter(s -> "ACTIVE".equals(s.getCopyingStatus()) && s.getBrokerAccountId() != null)
            .flatMap(s -> brokerRepo.findById(s.getBrokerAccountId())
                .filter(a -> !a.isSessionActive() || a.getSessionExpires() == null || a.getSessionExpires().isBefore(Instant.now()))
                .flatMap(a -> {
                    String brokerName = switch(a.getBrokerId()) {
                        case "GROWW" -> "Groww"; case "ZERODHA" -> "Zerodha"; case "FYERS" -> "Fyers";
                        case "UPSTOX" -> "Upstox"; case "DHAN" -> "Dhan"; default -> a.getBrokerId();
                    };
                    return notifications.push(s.getChildId(), "Broker login required",
                            "Your " + brokerName + " session expired. Login before 9:15 AM to resume copy trading.",
                            "SESSION_REMINDER");
                }))
            .subscribe();
    }
}
