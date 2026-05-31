package com.copytrading.engine;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.trade.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Safety-net reconciliation.
 *
 * <p>If a placement response is lost (e.g. network drop) the engine's trade record can be left in a
 * stale/unknown state forever, because nothing re-checks it. This job periodically pulls each active
 * broker account's orderbook (the source of truth) and syncs our {@code trades} records to the broker's
 * current terminal status, so an order is never stuck in an unknown state.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;
    private final TradeRepository tradeRepo;
    private final CanonicalOrderMapper canonicalMapper;

    private volatile boolean running = false;

    public ReconciliationService(BrokerAccountRepository brokerRepo,
                                 BrokerAccountService brokerService,
                                 TradeRepository tradeRepo,
                                 CanonicalOrderMapper canonicalMapper) {
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.tradeRepo = tradeRepo;
        this.canonicalMapper = canonicalMapper;
    }

    /**
     * Reconcile every active broker account's orderbook with our trade records.
     * Interval from {@code engine.reconciliation.interval-ms} (default 120000ms).
     */
    @Scheduled(fixedDelayString = "${engine.reconciliation.interval-ms:120000}",
            initialDelayString = "${engine.reconciliation.initial-delay-ms:60000}")
    public void reconcile() {
        if (running) return; // skip if a previous run is still in flight
        running = true;
        brokerRepo.findAll()
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .flatMap(this::reconcileAccount, 4)
                .reduce(0L, Long::sum)
                .doFinally(s -> running = false)
                .subscribe(
                        updated -> { if (updated > 0) log.info("RECONCILE_DONE updatedTrades={}", updated); },
                        err -> log.warn("RECONCILE_ERROR {}", err.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Mono<Long> reconcileAccount(BrokerAccount account) {
        return brokerService.getOrders(account.getId(), account.getUserId())
                .flatMapMany(resp -> Flux.fromIterable(extractOrders(resp.get("orders"))))
                .filter(o -> o instanceof Map)
                .flatMap(o -> reconcileOrder(account, (Map<String, Object>) o))
                .reduce(0L, Long::sum)
                .onErrorResume(e -> {
                    log.debug("RECONCILE_ACCOUNT_FAIL account={} broker={} error={}",
                            account.getId(), account.getBrokerId(), e.getMessage());
                    return Mono.just(0L);
                });
    }

    private Mono<Long> reconcileOrder(BrokerAccount account, Map<String, Object> raw) {
        CanonicalOrder canonical = canonicalMapper.fromBrokerOrder(raw, account.getBrokerId());
        String orderId = canonical.getOrderId();
        String canonStatus = canonical.getStatus();
        if (orderId == null || canonStatus == null || !BrokerStatusNormalizer.isTerminal(canonStatus)) {
            return Mono.just(0L);
        }
        String desired = toTradeStatus(canonStatus);
        return tradeRepo.findByUserIdAndBrokerOrderId(account.getUserId(), orderId)
                .filter(t -> !desired.equalsIgnoreCase(t.getStatus()))
                .flatMap(t -> {
                    String prev = t.getStatus();
                    t.setStatus(desired);
                    if ("EXECUTED".equals(desired) && t.getExecutedAt() == null) {
                        t.setExecutedAt(Instant.now());
                    }
                    if (("CANCELLED".equals(desired) || "REJECTED".equals(desired)) && t.getCancelledAt() == null) {
                        t.setCancelledAt(Instant.now());
                    }
                    return tradeRepo.save(t).doOnSuccess(s -> log.info(
                            "RECONCILE_TRADE_SYNCED account={} broker={} orderId={} {} -> {}",
                            account.getId(), account.getBrokerId(), orderId, prev, desired));
                })
                .map(t -> 1L)
                .defaultIfEmpty(0L);
    }

    /** Canonical broker status -> internal trade status vocabulary. */
    private static String toTradeStatus(String canonical) {
        return switch (canonical) {
            case BrokerStatusNormalizer.COMPLETE, BrokerStatusNormalizer.CLOSED -> "EXECUTED";
            case BrokerStatusNormalizer.CANCELLED, BrokerStatusNormalizer.EXPIRED -> "CANCELLED";
            case BrokerStatusNormalizer.REJECTED -> "REJECTED";
            default -> canonical;
        };
    }

    private static List<?> extractOrders(Object ordersObj) {
        if (ordersObj instanceof List<?> list) return list;
        if (ordersObj instanceof Map<?, ?> map) {
            Object inner = map.get("order_list");
            if (inner == null) inner = map.get("orderBook");
            if (inner == null) inner = map.get("data");
            if (inner instanceof List<?> list2) return list2;
        }
        return List.of();
    }
}
