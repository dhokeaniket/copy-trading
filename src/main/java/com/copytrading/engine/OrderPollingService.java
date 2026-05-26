package com.copytrading.engine;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.cache.PollingStateCache;
import com.copytrading.config.EnginePollingProperties;
import com.copytrading.master.MasterActiveAccount;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.notification.NotificationService;
import com.copytrading.subscription.SubscriptionRepository;
import com.copytrading.trade.Trade;
import com.copytrading.trade.TradeRepository;
import com.copytrading.ws.TradeUpdatesHub;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls each master's active broker account on a configurable interval (default 500ms).
 * When a new filled order is detected, triggers {@link CopyEngineService#copyTrade}.
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
    private final CanonicalOrderMapper canonicalMapper;
    private final EnginePollingProperties pollingProperties;

    // In-memory fallback for known orders (used alongside Redis)
    private final ConcurrentHashMap<UUID, Set<String>> knownOrders = new ConcurrentHashMap<>();

    // Lock to prevent same order being processed concurrently by multiple poll cycles
    private final Set<String> processingOrders = ConcurrentHashMap.newKeySet();

    // Toggle polling on/off — defaults to true, restored from Redis on startup
    private volatile boolean pollingEnabled = true;

    // Temporarily pause polling during cache reset to prevent race conditions
    private volatile boolean resetInProgress = false;

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
                               NotificationService notifications,
                               CanonicalOrderMapper canonicalMapper,
                               EnginePollingProperties pollingProperties) {
        this.activeAccountRepo = activeAccountRepo;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.subs = subs;
        this.copyEngine = copyEngine;
        this.tradeRepo = tradeRepo;
        this.hub = hub;
        this.pollingCache = pollingCache;
        this.notifications = notifications;
        this.canonicalMapper = canonicalMapper;
        this.pollingProperties = pollingProperties;
    }

    public long getPollingIntervalMs() {
        return pollingProperties.getIntervalMs();
    }

    public boolean isPollingEnabled() { return pollingEnabled; }
    public void setPollingEnabled(boolean enabled) {
        this.pollingEnabled = enabled;
        // Persist to Redis so it survives restarts
        pollingCache.setPollingEnabled(enabled).subscribe();
    }
    public Instant getLastResetAt() { return lastResetAt; }

    /** Restore polling state from Redis on startup. Defaults to ON if not set. */
    @PostConstruct
    public void restorePollingState() {
        pollingCache.getPollingEnabled()
                .subscribe(
                        enabled -> {
                            this.pollingEnabled = enabled;
                            log.info("POLLING_STATE_RESTORED from Redis: enabled={}", enabled);
                        },
                        err -> {
                            this.pollingEnabled = true; // Default ON
                            log.warn("POLLING_STATE_RESTORE_FAILED, defaulting to ON: {}", err.getMessage());
                        }
                );
        // Restore known orders from Redis so we don't re-process orders after restart
        pollingCache.loadAllKnownOrders()
                .subscribe(
                        restored -> {
                            restored.forEach((masterId, orderIds) -> {
                                knownOrders.computeIfAbsent(masterId, k -> ConcurrentHashMap.newKeySet()).addAll(orderIds);
                                log.info("KNOWN_ORDERS_RESTORED master={} count={}", masterId, orderIds.size());
                            });
                        },
                        err -> log.warn("KNOWN_ORDERS_RESTORE_FAILED: {}", err.getMessage())
                );
        // Also load today's trades from DB as known orders (belt and suspenders)
        activeAccountRepo.findAll()
                .flatMap(active -> tradeRepo.findByUserIdOrderByPlacedAtDesc(active.getMasterId())
                        .filter(t -> t.getBrokerOrderId() != null && !t.getBrokerOrderId().isBlank())
                        .map(t -> Map.entry(active.getMasterId(), t.getBrokerOrderId())))
                .subscribe(
                        entry -> knownOrders.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet()).add(entry.getValue()),
                        err -> log.warn("DB_KNOWN_ORDERS_LOAD_FAILED: {}", err.getMessage())
                );
    }

    /**
     * Polls all masters with active broker sessions. Interval from {@code engine.polling.interval-ms} (default 500ms).
     */
    @Scheduled(fixedDelayString = "${engine.polling.interval-ms:500}",
            initialDelayString = "${engine.polling.initial-delay-ms:15000}")
    public void pollMasterOrders() {
        if (!pollingEnabled || resetInProgress) return;

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
        Set<String> known = knownOrders.computeIfAbsent(masterId, k -> ConcurrentHashMap.newKeySet());
        int newCount = 0;

        for (Object orderObj : ordersList) {
            if (!(orderObj instanceof Map)) continue;
            Map<String, Object> order = (Map<String, Object>) orderObj;

            CanonicalOrder canonical = canonicalMapper.fromBrokerOrder(order, account.getBrokerId());
            if (!canonical.isReadyForCopy()) continue;

            String orderId = canonical.getOrderId();
            String symbol = canonical.getSymbol();
            String side = canonical.getSide();
            int qty = canonical.getFilledQty();

            // 3-layer dedup: in-memory set → processingOrders lock → (DB checked on startup/reset)
            if (known.contains(orderId)) continue;
            if (!processingOrders.add(orderId)) continue;
            known.add(orderId);

            pollingCache.markOrderKnown(masterId, orderId).subscribe();

            log.info("NEW_ORDER_DETECTED master={} broker={} orderId={} symbol={} side={} qty={} segment={}",
                    masterId, account.getBrokerId(), orderId, symbol, side, qty, canonical.getSegment());

            Trade masterTrade = canonicalMapper.toMasterTrade(canonical, masterId, account.getId());
            tradeRepo.save(masterTrade).subscribe(
                    saved -> log.info("MASTER_TRADE_SAVED id={} symbol={}", saved.getId(), symbol),
                    err -> log.warn("MASTER_TRADE_SAVE_FAIL: {}", err.getMessage())
            );

            hub.publish("{\"event\":\"TRADE_DETECTED\",\"masterId\":\"" + masterId +
                    "\",\"instrument\":\"" + symbol + "\",\"side\":\"" + side +
                    "\",\"qty\":" + qty + ",\"broker\":\"" + account.getBrokerId() + "\"}");

            CopyTradeRequest req = canonicalMapper.toCopyTradeRequest(canonical);

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

    /** Clear known orders for a master (e.g. at start of new trading day) */
    public void resetKnownOrders(UUID masterId) {
        knownOrders.remove(masterId);
        pollingCache.resetMaster(masterId).subscribe();
    }

    public void resetAllKnownOrders() {
        resetInProgress = true;
        knownOrders.clear();
        processingOrders.clear();
        pollingCache.resetAll().subscribe();
        // Reload known orders from DB so old trades don't get re-triggered
        activeAccountRepo.findAll()
                .flatMap(active -> tradeRepo.findByUserIdOrderByPlacedAtDesc(active.getMasterId())
                        .filter(t -> t.getBrokerOrderId() != null && !t.getBrokerOrderId().isBlank())
                        .map(t -> Map.entry(active.getMasterId(), t.getBrokerOrderId())))
                .doOnNext(entry -> knownOrders.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet()).add(entry.getValue()))
                .doOnError(err -> log.warn("DB_RELOAD_AFTER_RESET_FAILED: {}", err.getMessage()))
                .doFinally(signal -> {
                    resetInProgress = false;
                    log.info("RESET_COMPLETE: knownOrders reloaded from DB, polling resumed");
                })
                .subscribe();
    }

    // Auto-reset polling cache at 9:15 AM IST (3:45 AM UTC) on weekdays
    @Scheduled(cron = "0 45 3 * * MON-FRI")
    public void autoResetPollingCache() {
        log.info("AUTO_RESET_POLLING: Clearing known orders cache at market open");
        resetAllKnownOrders(); // clears + reloads from DB to prevent re-triggering old orders
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
