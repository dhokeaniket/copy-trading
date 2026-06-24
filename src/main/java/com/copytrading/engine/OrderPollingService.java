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

    // Temporarily pause polling for specific masters during snapshot
    private final Set<UUID> snapshotInProgressMasters = ConcurrentHashMap.newKeySet();

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
        boolean wasDisabled = !this.pollingEnabled;
        this.pollingEnabled = enabled;
        // Persist to Redis so it survives restarts
        pollingCache.setPollingEnabled(enabled).subscribe();
        // When resuming from OFF→ON, block polls until we've marked the full current order book
        // as known. Otherwise the next poll cycle can run before the async snapshot finishes and
        // would copy fills that happened while polling was off.
        if (enabled && wasDisabled) {
            log.info("POLLING_RESUMED — snapshotting current orders (polls paused until complete)");
            resetInProgress = true;
            snapshotCurrentOrdersReactive()
                    .doFinally(signal -> {
                        resetInProgress = false;
                        log.info("POLLING_SNAPSHOT_DONE signal={} — polls resumed", signal);
                    })
                    .subscribe();
        }
        log.info("POLLING_STATE_CHANGED enabled={}", enabled);
    }

    /**
     * Mark all current master orders as "known" across every linked broker account that has a token
     * (same coverage as {@link #pollSingleMaster}), so fills from the "off" period never copy after resume.
     */
    private Mono<Void> snapshotCurrentOrdersReactive() {
        return activeAccountRepo.findAll()
                .map(MasterActiveAccount::getMasterId)
                .distinct()
                .flatMap(this::snapshotSingleMasterOrders)
                .then();
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
        brokerRepo.findPollableMasterAccounts()
                .doOnNext(account -> seenBrokerAccounts.add(account.getId())) // Mark as seen so first-poll doesn't re-snapshot
                .flatMap(account -> tradeRepo.findByUserIdOrderByPlacedAtDesc(account.getUserId())
                        .filter(t -> t.getBrokerOrderId() != null && !t.getBrokerOrderId().isBlank())
                        .map(t -> Map.entry(account.getUserId(), t.getBrokerOrderId())))
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

        // Poll ALL broker accounts where session is active AND is_copy_enable=true
        // for any user who is a master (has active subscribers).
        // This allows masters with multiple broker accounts to have all of them polled.
        brokerRepo.findPollableMasterAccounts()
                .flatMap(account -> pollBrokerAccount(account)
                        .onErrorResume(e -> {
                            log.warn("POLL_ERROR master={} broker={} error={}", account.getUserId(), account.getBrokerId(), e.getMessage());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    public Mono<Void> snapshotSingleMasterOrders(UUID masterId) {
        return Mono.defer(() -> {
            snapshotInProgressMasters.add(masterId);
            return brokerRepo.findByUserId(masterId)
                    .filter(a -> a.getAccessToken() != null)
                    .flatMap(account -> brokerService.getOrders(account.getId(), masterId)
                            .map(resp -> {
                                List<?> ordersList = extractOrdersList(resp.get("orders"));
                                if (ordersList != null && !ordersList.isEmpty()) {
                                    Set<String> known = knownOrders.computeIfAbsent(masterId, k -> ConcurrentHashMap.newKeySet());
                                    int marked = 0;
                                    for (Object orderObj : ordersList) {
                                        if (orderObj instanceof Map<?, ?> order) {
                                            String orderId = OrderNormalizer.extractOrderId(toStringKeyMap(order));
                                            if (orderId != null && !orderId.isBlank()) {
                                                known.add(orderId);
                                                pollingCache.markOrderKnown(masterId, orderId).subscribe();
                                                marked++;
                                            }
                                        }
                                    }
                                    log.info("POLLING_SNAPSHOT master={} broker={} ordersMarkedKnown={}",
                                            masterId, account.getBrokerId(), marked);
                                }
                                return 0;
                            })
                            .onErrorResume(e -> {
                                log.warn("POLLING_SNAPSHOT_FAIL master={} broker={} err={}",
                                        masterId, account.getBrokerId(), e.getMessage());
                                return Mono.just(0);
                            }))
                    .then()
                    .doFinally(signal -> snapshotInProgressMasters.remove(masterId));
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private Mono<Void> pollSingleMaster(MasterActiveAccount active) {
        UUID masterId = active.getMasterId();
        if (snapshotInProgressMasters.contains(masterId)) {
            return Mono.empty();
        }

        // Poll ONLY the designated active broker account
        return brokerRepo.findById(active.getBrokerAccountId())
                .filter(a -> a.getAccessToken() != null)
                .flatMap(account -> brokerService.getOrders(account.getId(), masterId)
                        .map(resp -> {
                            List<?> ordersList = extractOrdersList(resp.get("orders"));
                            if (ordersList != null && !ordersList.isEmpty()) {
                                return processOrders(masterId, account, ordersList);
                            }
                            return 0;
                        })
                        .onErrorResume(e -> {
                            log.debug("POLL_ORDERS_FAIL master={} broker={} error={}", masterId, account.getBrokerId(), e.getMessage());
                            return Mono.just(0);
                        }))
                .then();
    }

    /**
     * Poll a single broker account directly (used by the new multi-account polling).
     * The account's user_id IS the master_id.
     *
     * First-poll protection: when a broker account is seen for the first time (e.g., user just
     * toggled isCopyEnable ON or logged in), we snapshot all its existing orders as "known" so
     * they don't get re-copied. Only genuinely NEW orders placed AFTER this point get copied.
     */
    private final Set<UUID> seenBrokerAccounts = ConcurrentHashMap.newKeySet();

    private Mono<Void> pollBrokerAccount(BrokerAccount account) {
        UUID masterId = account.getUserId();
        if (snapshotInProgressMasters.contains(masterId)) {
            return Mono.empty();
        }
        return brokerService.getOrders(account.getId(), masterId)
                .map(resp -> {
                    List<?> ordersList = extractOrdersList(resp.get("orders"));
                    if (ordersList == null || ordersList.isEmpty()) return 0;

                    // First time seeing this broker account? Snapshot all orders as known — don't re-copy.
                    if (seenBrokerAccounts.add(account.getId())) {
                        Set<String> known = knownOrders.computeIfAbsent(masterId, k -> ConcurrentHashMap.newKeySet());
                        int snapshotCount = 0;
                        for (Object orderObj : ordersList) {
                            if (orderObj instanceof Map<?, ?> order) {
                                String orderId = OrderNormalizer.extractOrderId(toStringKeyMap(order));
                                if (orderId != null && !orderId.isBlank()) {
                                    known.add(orderId);
                                    snapshotCount++;
                                }
                            }
                        }
                        log.info("POLL_FIRST_SEEN_SNAPSHOT broker={} accountId={} master={} ordersMarkedKnown={}",
                                account.getBrokerId(), account.getId(), masterId, snapshotCount);
                        return 0; // Don't process on first poll — just snapshot
                    }

                    return processOrders(masterId, account, ordersList);
                })
                .onErrorResume(e -> {
                    log.debug("POLL_ORDERS_FAIL master={} broker={} error={}", masterId, account.getBrokerId(), e.getMessage());
                    return Mono.just(0);
                })
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

            // Cancel propagation: a master order that was cancelled at the exchange should cancel any
            // linked child orders. Propagation is idempotent so repeated polls don't re-cancel.
            if (BrokerStatusNormalizer.CANCELLED.equals(canonical.getStatus())
                    && canonical.getOrderId() != null) {
                copyEngine.propagateCancel(masterId, canonical.getOrderId()).subscribe();
            }

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
            req.setMasterBrokerId(account.getBrokerId());

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
        seenBrokerAccounts.clear(); // Force fresh snapshot on next poll for each broker account
        pollingCache.resetAll().subscribe();
        // Reload known orders from DB so old trades don't get re-triggered
        brokerRepo.findPollableMasterAccounts()
                .flatMap(account -> tradeRepo.findByUserIdOrderByPlacedAtDesc(account.getUserId())
                        .filter(t -> t.getBrokerOrderId() != null && !t.getBrokerOrderId().isBlank())
                        .map(t -> Map.entry(account.getUserId(), t.getBrokerOrderId())))
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

    private static List<?> extractOrdersList(Object ordersObj) {
        if (ordersObj instanceof List<?> list) {
            return list;
        }
        if (ordersObj instanceof Map<?, ?> map) {
            for (String key : List.of("order_list", "orderBook", "data", "orders")) {
                Object inner = map.get(key);
                if (inner instanceof List<?> list2) {
                    return list2;
                }
            }
        }
        return null;
    }
}
