package com.copytrading.engine;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.security.BrokerCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket-based order stream listener for supported brokers.
 *
 * Connects to broker WebSocket feeds (Upstox Portfolio Stream, etc.) to receive
 * real-time order updates. This eliminates polling delay — orders are detected
 * within milliseconds of execution.
 *
 * Falls back to REST polling (OrderPollingService) for brokers without WebSocket
 * support or when WebSocket connection fails.
 *
 * Supported:
 *   - Upstox: wss://api.upstox.com/v2/feed/portfolio-stream-feed
 *   - Zerodha: postback (already implemented via webhook, not WS)
 *   - Groww: WebSocket order updates (SDK-based)
 *   - Angel One: SmartAPI WebSocket
 */
@Service
public class BrokerWebSocketService {

    private static final Logger log = LoggerFactory.getLogger(BrokerWebSocketService.class);

    private final BrokerAccountRepository brokerRepo;
    private final BrokerCredentials credentials;
    private final CopyEngineService copyEngine;
    private final CanonicalOrderMapper canonicalMapper;
    private final OrderPollingService pollingService;

    // Active WebSocket connections per broker account ID
    private final ConcurrentHashMap<UUID, Disposable> activeConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    public BrokerWebSocketService(BrokerAccountRepository brokerRepo,
                                   BrokerCredentials credentials,
                                   CopyEngineService copyEngine,
                                   CanonicalOrderMapper canonicalMapper,
                                   OrderPollingService pollingService) {
        this.brokerRepo = brokerRepo;
        this.credentials = credentials;
        this.copyEngine = copyEngine;
        this.canonicalMapper = canonicalMapper;
        this.pollingService = pollingService;
    }

    /**
     * Periodically check for pollable master accounts and connect WebSocket where supported.
     * Runs every 30 seconds — connects new accounts, reconnects dropped ones.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void maintainConnections() {
        brokerRepo.findPollableMasterAccounts()
                .filter(a -> "UPSTOX".equals(a.getBrokerId())) // Start with Upstox only
                .filter(a -> !activeConnections.containsKey(a.getId())
                        || isConnectionStale(a.getId()))
                .subscribe(account -> connectUpstox(account));
    }

    private boolean isConnectionStale(UUID accountId) {
        Long lastMsg = lastMessageAt.get(accountId);
        // If no message in 5 minutes, reconnect (market hours have frequent updates)
        return lastMsg != null && (System.currentTimeMillis() - lastMsg) > 300_000;
    }

    /**
     * Connect to Upstox Portfolio Stream WebSocket.
     * URL: wss://api.upstox.com/v2/feed/portfolio-stream-feed
     * Auth: Bearer token in header, auto-redirects to actual WS endpoint.
     */
    private void connectUpstox(BrokerAccount account) {
        String token = credentials.accessToken(account);
        if (token == null || token.isBlank()) return;

        // Disconnect existing if any
        disconnectAccount(account.getId());

        String wsUrl = "wss://api.upstox.com/v2/feed/portfolio-stream-feed?update_types=order";

        log.info("WS_CONNECT_START broker=UPSTOX accountId={} master={}", account.getId(), account.getUserId());

        try {
            Disposable connection = HttpClient.create()
                    .headers(h -> {
                        h.add("Authorization", "Bearer " + token);
                        h.add("Accept", "*/*");
                    })
                    .websocket(WebsocketClientSpec.builder()
                            .handlePing(true)
                            .build())
                    .uri(URI.create(wsUrl))
                    .handle((inbound, outbound) -> {
                        return inbound.receive()
                                .asString()
                                .doOnNext(msg -> handleUpstoxMessage(account, msg))
                                .doOnError(e -> {
                                    log.warn("WS_ERROR broker=UPSTOX accountId={} error={}",
                                            account.getId(), e.getMessage());
                                    activeConnections.remove(account.getId());
                                })
                                .doOnComplete(() -> {
                                    log.info("WS_DISCONNECTED broker=UPSTOX accountId={}", account.getId());
                                    activeConnections.remove(account.getId());
                                })
                                .then();
                    })
                    .subscribe();

            activeConnections.put(account.getId(), connection);
            log.info("WS_CONNECTED broker=UPSTOX accountId={} master={}", account.getId(), account.getUserId());

        } catch (Exception e) {
            log.error("WS_CONNECT_FAILED broker=UPSTOX accountId={} error={}", account.getId(), e.getMessage());
        }
    }

    /**
     * Handle incoming Upstox WebSocket order update message.
     * Parses the JSON, checks if it's a completed order, and triggers copy.
     */
    @SuppressWarnings("unchecked")
    private void handleUpstoxMessage(BrokerAccount account, String message) {
        lastMessageAt.put(account.getId(), System.currentTimeMillis());

        try {
            // Parse JSON
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> msg = mapper.readValue(message, Map.class);

            String updateType = (String) msg.get("update_type");
            if (!"order".equals(updateType)) return; // Only process order updates

            String status = (String) msg.get("status");
            String orderId = (String) msg.get("order_id");
            Integer filledQty = msg.get("filled_quantity") != null
                    ? ((Number) msg.get("filled_quantity")).intValue() : 0;

            // Only copy when order is fully/partially filled
            if (filledQty <= 0) return;
            String normalizedStatus = BrokerStatusNormalizer.toCanonical(status, "UPSTOX");
            if (!"COMPLETE".equals(normalizedStatus) && !"TRADED".equals(normalizedStatus)) return;

            UUID masterId = account.getUserId();

            // Dedup: check if already processed by polling
            Set<String> known = pollingService.getKnownOrders(masterId);
            if (known != null && known.contains(orderId)) return;
            if (known != null) known.add(orderId);

            log.info("WS_ORDER_DETECTED broker=UPSTOX master={} orderId={} symbol={} side={} qty={} latency=REALTIME",
                    masterId, orderId, msg.get("trading_symbol"), msg.get("transaction_type"), filledQty);

            // Build canonical order and trigger copy
            CanonicalOrder canonical = canonicalMapper.fromBrokerOrder(msg, "UPSTOX");
            if (!canonical.isReadyForCopy()) return;

            CopyTradeRequest req = canonicalMapper.toCopyTradeRequest(canonical);
            req.setMasterBrokerId("UPSTOX");
            req.setMasterOrderId(orderId);

            copyEngine.copyTrade(masterId, req)
                    .subscribe(
                            result -> log.info("WS_COPY_DONE master={} symbol={} result={}",
                                    masterId, canonical.getSymbol(),
                                    result.get("success") + "/" + result.get("childrenTotal")),
                            error -> log.error("WS_COPY_ERROR master={} symbol={} error={}",
                                    masterId, canonical.getSymbol(), error.getMessage())
                    );

        } catch (Exception e) {
            // Not all messages are JSON (ping/pong) — ignore parse errors silently
            if (message.length() > 10) {
                log.debug("WS_PARSE_SKIP broker=UPSTOX msg={}", message.substring(0, Math.min(100, message.length())));
            }
        }
    }

    /** Disconnect a specific account's WebSocket */
    public void disconnectAccount(UUID accountId) {
        Disposable existing = activeConnections.remove(accountId);
        if (existing != null && !existing.isDisposed()) {
            existing.dispose();
            log.info("WS_DISCONNECT accountId={}", accountId);
        }
    }

    /** Disconnect all WebSocket connections (e.g., on shutdown) */
    public void disconnectAll() {
        activeConnections.forEach((id, conn) -> {
            if (!conn.isDisposed()) conn.dispose();
        });
        activeConnections.clear();
        log.info("WS_DISCONNECT_ALL count={}", activeConnections.size());
    }

    /** Get status of all active connections */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeConnections", activeConnections.size());
        status.put("accounts", new ArrayList<>());
        activeConnections.forEach((id, conn) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("accountId", id);
            info.put("connected", !conn.isDisposed());
            info.put("lastMessageAt", lastMessageAt.get(id));
            ((List<Map<String, Object>>) status.get("accounts")).add(info);
        });
        return status;
    }
}
