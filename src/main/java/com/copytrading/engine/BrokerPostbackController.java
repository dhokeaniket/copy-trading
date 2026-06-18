package com.copytrading.engine;

import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.PlatformBrokerConfig;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.trade.TradeRepository;
import com.copytrading.ws.TradeUpdatesHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Receives instant order updates from brokers that support postback/webhook.
 * 
 * Zerodha Postback: POST /api/v1/brokers/postback/zerodha
 *   - Set this URL in Zerodha developer console as "Postback URL"
 *   - Zerodha POSTs JSON when order status changes (COMPLETE, CANCEL, REJECTED)
 *   
 * This gives ~100ms detection vs 1-3s polling.
 */
@RestController
@RequestMapping("/api/v1/brokers/postback")
public class BrokerPostbackController {

    private static final Logger log = LoggerFactory.getLogger(BrokerPostbackController.class);

    private final CopyEngineService copyEngine;
    private final BrokerAccountRepository brokerRepo;
    private final TradeRepository tradeRepo;
    private final TradeUpdatesHub hub;
    private final CanonicalOrderMapper canonicalMapper;
    private final PlatformBrokerConfig platformConfig;
    private final OrderPollingService orderPollingService;
    private final MasterActiveAccountRepository activeAccountRepo;

    public BrokerPostbackController(CopyEngineService copyEngine, BrokerAccountRepository brokerRepo,
                                     TradeRepository tradeRepo, TradeUpdatesHub hub,
                                     CanonicalOrderMapper canonicalMapper,
                                     PlatformBrokerConfig platformConfig,
                                     OrderPollingService orderPollingService,
                                     MasterActiveAccountRepository activeAccountRepo) {
        this.copyEngine = copyEngine;
        this.brokerRepo = brokerRepo;
        this.tradeRepo = tradeRepo;
        this.hub = hub;
        this.canonicalMapper = canonicalMapper;
        this.platformConfig = platformConfig;
        this.orderPollingService = orderPollingService;
        this.activeAccountRepo = activeAccountRepo;
    }

    /**
     * Zerodha Postback — receives order updates instantly.
     * Zerodha sends: { order_id, status, tradingsymbol, transaction_type, quantity, ..., checksum }
     *
     * <p>Authenticity is verified with SHA-256(order_id + order_timestamp + api_secret), matched against
     * the {@code X-Zerodha-Checksum} header (or the {@code checksum} body field). Any postback that fails
     * verification is rejected before it can create real child orders.
     */
    @PostMapping(value = "/zerodha", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> zerodhaPostback(@RequestBody Map<String, Object> payload,
                                                      @RequestHeader(value = "X-Zerodha-Checksum", required = false) String checksumHeader) {
        log.info("ZERODHA_POSTBACK received: {}", payload);

        if (!isValidZerodhaChecksum(payload, checksumHeader)) {
            log.warn("ZERODHA_POSTBACK_REJECTED invalid/missing checksum order_id={}", payload.get("order_id"));
            return Mono.just(Map.of("message", "Rejected: invalid postback checksum"));
        }

        CanonicalOrder canonical = canonicalMapper.fromBrokerOrder(payload, "ZERODHA");

        String userId = String.valueOf(payload.getOrDefault("user_id", ""));

        // Cancel propagation: master cancelled at the exchange -> cancel linked child orders.
        if (BrokerStatusNormalizer.CANCELLED.equals(canonical.getStatus()) && canonical.getOrderId() != null) {
            return brokerRepo.findByBrokerId("ZERODHA")
                    .filter(a -> a.getClientId() != null && a.getClientId().equals(userId))
                    .next()
                    .flatMap(account -> copyEngine.propagateCancel(account.getUserId(), canonical.getOrderId())
                            .map(n -> Map.of("message", "Cancel propagated to " + n + " child order(s)")))
                    .defaultIfEmpty(Map.of("message", "No matching master account found for user_id: " + userId));
        }

        if (!canonical.isReadyForCopy()) {
            return Mono.just(Map.of("message", "Ignored order status: " + canonical.getStatus()));
        }

        if (!orderPollingService.isPollingEnabled()) {
            log.info("ZERODHA_POSTBACK_COPY_SKIPPED master order — auto-copy is paused (polling off)");
            return Mono.just(Map.of("message", "Copy trading paused — fill not replicated"));
        }

        return brokerRepo.findByBrokerId("ZERODHA")
                .filter(a -> a.getClientId() != null && a.getClientId().equals(userId))
                .next()
                .flatMap(account -> activeAccountRepo.findByMasterId(account.getUserId())
                    .filter(active -> active.getBrokerAccountId().equals(account.getId()))
                    .next()
                    .map(active -> account))
                .flatMap(account -> {
                    UUID masterId = account.getUserId();
                    canonical.setSourceBrokerId(account.getBrokerId());
                    log.info("ZERODHA_POSTBACK_MATCHED master={} symbol={} side={} qty={}",
                            masterId, canonical.getSymbol(), canonical.getSide(), canonical.getFilledQty());

                    tradeRepo.save(canonicalMapper.toMasterTrade(canonical, masterId, account.getId())).subscribe();

                    hub.publish("{\"event\":\"TRADE_DETECTED\",\"source\":\"POSTBACK\",\"broker\":\"ZERODHA\",\"instrument\":\""
                            + canonical.getSymbol() + "\",\"side\":\"" + canonical.getSide() + "\",\"qty\":"
                            + canonical.getFilledQty() + ",\"latency\":\"<100ms\"}");

                    return copyEngine.copyTrade(masterId, canonicalMapper.toCopyTradeRequest(canonical))
                            .thenReturn(Map.of("message", "Trade detected and copied via postback"));
                })
                .defaultIfEmpty(Map.of("message", "No matching master account found for user_id: " + userId));
    }

    /**
     * Verify the Zerodha postback signature: SHA-256(order_id + order_timestamp + api_secret).
     * Returns false when the api secret is configured but the checksum is missing or does not match.
     * When no api secret is configured we cannot verify, so we allow it (dev / unconfigured environments).
     */
    private boolean isValidZerodhaChecksum(Map<String, Object> payload, String checksumHeader) {
        String apiSecret = platformConfig.getZerodha() != null ? platformConfig.getZerodha().getApiSecret() : null;
        if (apiSecret == null || apiSecret.isBlank()) {
            log.debug("ZERODHA_POSTBACK_CHECKSUM_SKIPPED no api secret configured");
            return true;
        }
        String provided = checksumHeader != null && !checksumHeader.isBlank()
                ? checksumHeader.trim()
                : str(payload.get("checksum"));
        if (provided == null || provided.isBlank()) return false;

        String orderId = str(payload.get("order_id"));
        String orderTimestamp = str(payload.get("order_timestamp"));
        if (orderId == null) return false;
        String expected = sha256Hex((orderId) + (orderTimestamp != null ? orderTimestamp : "") + apiSecret);
        return expected != null && expected.equalsIgnoreCase(provided);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
