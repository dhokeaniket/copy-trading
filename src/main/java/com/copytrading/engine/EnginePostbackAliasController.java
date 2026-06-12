package com.copytrading.engine;

import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.trade.TradeRepository;
import com.copytrading.ws.TradeUpdatesHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/** Spec path: {@code POST /api/v1/engine/postback/zerodha}. */
@RestController
@RequestMapping("/api/v1/engine/postback")
public class EnginePostbackAliasController {

    private final CopyEngineService copyEngine;
    private final BrokerAccountRepository brokerRepo;
    private final TradeRepository tradeRepo;
    private final TradeUpdatesHub hub;
    private final CanonicalOrderMapper canonicalMapper;
    private final OrderPollingService orderPollingService;

    public EnginePostbackAliasController(CopyEngineService copyEngine, BrokerAccountRepository brokerRepo,
                                         TradeRepository tradeRepo, TradeUpdatesHub hub,
                                         CanonicalOrderMapper canonicalMapper,
                                         OrderPollingService orderPollingService) {
        this.copyEngine = copyEngine;
        this.brokerRepo = brokerRepo;
        this.tradeRepo = tradeRepo;
        this.hub = hub;
        this.canonicalMapper = canonicalMapper;
        this.orderPollingService = orderPollingService;
    }

    @PostMapping(value = "/zerodha", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> zerodhaPostback(@RequestBody Map<String, Object> payload) {
        CanonicalOrder canonical = canonicalMapper.fromBrokerOrder(payload, "ZERODHA");
        String userId = String.valueOf(payload.getOrDefault("user_id", ""));

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
            return Mono.just(Map.of("message", "Copy trading paused — fill not replicated"));
        }
        return brokerRepo.findByBrokerId("ZERODHA")
                .filter(a -> a.getClientId() != null && a.getClientId().equals(userId))
                .next()
                .flatMap(account -> {
                    UUID masterId = account.getUserId();
                    canonical.setSourceBrokerId(account.getBrokerId());
                    tradeRepo.save(canonicalMapper.toMasterTrade(canonical, masterId, account.getId())).subscribe();
                    hub.publish("{\"event\":\"TRADE_DETECTED\",\"source\":\"POSTBACK\",\"broker\":\"ZERODHA\"}");
                    return copyEngine.copyTrade(masterId, canonicalMapper.toCopyTradeRequest(canonical))
                            .thenReturn(Map.of("message", "Trade detected and copied via postback"));
                })
                .defaultIfEmpty(Map.of("message", "No matching master account found for user_id: " + userId));
    }
}
