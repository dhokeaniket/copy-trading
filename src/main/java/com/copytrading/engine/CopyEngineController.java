package com.copytrading.engine;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/engine")
public class CopyEngineController {

    private final CopyEngineService copyEngine;
    private final OrderPollingService pollingService;

    public CopyEngineController(CopyEngineService copyEngine, OrderPollingService pollingService) {
        this.copyEngine = copyEngine;
        this.pollingService = pollingService;
    }

    /**
     * Manual trade copy — master triggers this to copy a trade to all active children.
     *
     * POST /api/v1/engine/copy-trade
     * {
     *   "symbol": "RELIANCE",
     *   "qty": 10,
     *   "side": "BUY",
     *   "product": "MIS",
     *   "orderType": "MARKET",
     *   "price": 0
     * }
     */
    @PostMapping(value = "/copy-trade", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> copyTrade(@AuthenticationPrincipal String userId,
                                                @RequestBody CopyTradeRequest req) {
        return copyEngine.copyTrade(UUID.fromString(userId), req);
    }

    /**
     * Engine status — is polling active, supported brokers, etc.
     */
    @GetMapping("/status")
    public Mono<Map<String, Object>> getStatus() {
        return copyEngine.getStatus().map(status -> {
            status.put("pollingEnabled", pollingService.isPollingEnabled());
            return status;
        });
    }

    /**
     * Enable/disable order polling.
     *
     * POST /api/v1/engine/polling
     * { "enabled": true }
     */
    @PostMapping(value = "/polling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> togglePolling(@RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        pollingService.setPollingEnabled(enabled);
        return Mono.just(Map.of(
                "pollingEnabled", enabled,
                "message", enabled ? "Polling started. Checking master orders every 10 seconds." : "Polling stopped."
        ));
    }

    /**
     * Reset known orders (e.g. at start of new trading day).
     * This makes the poller treat all current orders as "new" on next poll.
     */
    @PostMapping("/polling/reset")
    public Mono<Map<String, String>> resetPolling() {
        pollingService.resetAllKnownOrders();
        return Mono.just(Map.of("message", "Known orders cache cleared. Next poll will detect all orders as new."));
    }
}
