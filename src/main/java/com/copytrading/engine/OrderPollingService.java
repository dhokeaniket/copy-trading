package com.copytrading.engine;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.master.MasterActiveAccount;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    // masterId → Set of known order IDs (to detect new ones)
    private final ConcurrentHashMap<UUID, Set<String>> knownOrders = new ConcurrentHashMap<>();

    // Toggle polling on/off
    private volatile boolean pollingEnabled = false;

    public OrderPollingService(MasterActiveAccountRepository activeAccountRepo,
                               BrokerAccountRepository brokerRepo,
                               BrokerAccountService brokerService,
                               SubscriptionRepository subs,
                               CopyEngineService copyEngine) {
        this.activeAccountRepo = activeAccountRepo;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.subs = subs;
        this.copyEngine = copyEngine;
    }

    public boolean isPollingEnabled() { return pollingEnabled; }
    public void setPollingEnabled(boolean enabled) { this.pollingEnabled = enabled; }

    /**
     * Runs every 10 seconds. Checks each master's active broker account for new orders.
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 30000)
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
                            if (ordersObj instanceof List<?> ordersList) {
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

            String orderId = extractField(order, "order_id", "orderId", "id");
            String status = extractField(order, "status", "order_status");
            if (orderId == null || orderId.isBlank()) continue;

            // Only copy COMPLETE/EXECUTED orders that we haven't seen before
            if (known.contains(orderId)) continue;
            known.add(orderId);

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

            // Trigger copy
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
    }

    public void resetAllKnownOrders() {
        knownOrders.clear();
    }
}
