package com.copytrading.child;

import com.copytrading.child.dto.BulkSubscribeRequest;
import com.copytrading.child.dto.ChildScalingRequest;
import com.copytrading.child.dto.CopySettingsRequest;
import com.copytrading.child.dto.MasterIdRequest;
import com.copytrading.child.dto.SubscribeRequest;
import com.copytrading.positions.PositionsService;
import com.copytrading.trading.TradingDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/child")
@Tag(name = "4. Child", description = "Child trader: subscribe to masters, manage copying, analytics")
public class ChildController {

    private final ChildService service;
    private final PositionsService positionsService;
    private final TradingDataService tradingDataService;

    public ChildController(ChildService service, PositionsService positionsService,
                           TradingDataService tradingDataService) {
        this.service = service;
        this.positionsService = positionsService;
        this.tradingDataService = tradingDataService;
    }

    @GetMapping("/masters")
    public Mono<Map<String, Object>> listMasters(@AuthenticationPrincipal String userId) {
        return service.listMasters(UUID.fromString(userId));
    }

    @PostMapping(value = "/subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> subscribe(@AuthenticationPrincipal String userId,
                                                @RequestBody SubscribeRequest req) {
        return service.subscribe(UUID.fromString(userId), req.getMasterId(), req.getBrokerAccountId(),
                req.getScalingFactor(), req.getCopySides(), req.getAllowShortSelling());
    }

    @PostMapping(value = "/subscriptions/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> bulkSubscribe(@AuthenticationPrincipal String userId,
                                                    @RequestBody BulkSubscribeRequest req) {
        var masters = req.getMasters().stream().map(m -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("masterId", m.getMasterId().toString());
            if (m.getBrokerAccountId() != null) map.put("brokerAccountId", m.getBrokerAccountId().toString());
            if (m.getScalingFactor() != null) map.put("scalingFactor", m.getScalingFactor());
            return map;
        }).toList();
        return service.bulkSubscribe(UUID.fromString(userId), masters);
    }

    @DeleteMapping("/subscriptions/{masterId}")
    public Mono<Map<String, String>> unsubscribe(@AuthenticationPrincipal String userId, @PathVariable UUID masterId) {
        return service.unsubscribe(UUID.fromString(userId), masterId);
    }

    @Operation(summary = "Remove master (alias)", description = "Same as DELETE /subscriptions/{masterId}")
    @DeleteMapping({"/masters/{masterId}", "/remove/{masterId}"})
    public Mono<Map<String, String>> removeMaster(@AuthenticationPrincipal String userId, @PathVariable UUID masterId) {
        return service.unsubscribe(UUID.fromString(userId), masterId);
    }

    @PostMapping(value = "/subscriptions/bulk-unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> bulkUnsubscribe(@AuthenticationPrincipal String userId,
                                                      @RequestBody com.copytrading.child.dto.BulkUnsubscribeRequest req) {
        return service.bulkUnsubscribe(UUID.fromString(userId), req.getMasterIds());
    }

    @GetMapping("/subscriptions")
    public Mono<Map<String, Object>> listSubscriptions(@AuthenticationPrincipal String userId) {
        return service.listSubscriptions(UUID.fromString(userId));
    }

    @GetMapping("/scaling")
    public Mono<Map<String, Object>> getScaling(@AuthenticationPrincipal String userId,
                                                 @RequestParam(required = false) UUID masterId) {
        return service.getScaling(UUID.fromString(userId), masterId);
    }

    @PutMapping(value = "/scaling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateScaling(@AuthenticationPrincipal String userId,
                                                    @RequestBody ChildScalingRequest req) {
        return service.updateScaling(UUID.fromString(userId), req.getMasterId(), req.getScalingFactor());
    }

    @Operation(summary = "Update copy sides / short selling", description = "BUY_ONLY, BUY_AND_SELL, or MIRROR for a master subscription")
    @PatchMapping(value = "/subscriptions/copy-settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateCopySettings(@AuthenticationPrincipal String userId,
                                                          @RequestBody CopySettingsRequest req) {
        return service.updateCopySettings(UUID.fromString(userId), req.getMasterId(),
                req.getCopySides(), req.getAllowShortSelling(), req.getPriceTolerancePct());
    }

    @PostMapping(value = "/copying/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> pauseCopying(@AuthenticationPrincipal String userId,
                                                   @RequestBody MasterIdRequest req) {
        return service.pauseCopying(UUID.fromString(userId), req.getMasterId());
    }

    @PostMapping(value = "/copying/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resumeCopying(@AuthenticationPrincipal String userId,
                                                    @RequestBody MasterIdRequest req) {
        return service.resumeCopying(UUID.fromString(userId), req.getMasterId());
    }

    @GetMapping("/copied-trades")
    public Mono<Map<String, Object>> getCopiedTrades(@AuthenticationPrincipal String userId) {
        return service.getCopiedTrades(UUID.fromString(userId));
    }

    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }

    @GetMapping("/copy/logs")
    public Mono<Map<String, Object>> getCopyLogs(@AuthenticationPrincipal String userId) {
        return service.getCopyLogs(UUID.fromString(userId));
    }

    @Operation(summary = "Child copy trade timeline", description = "Spec §1 — latency timeline for child user")
    @GetMapping("/trade-timeline")
    public Mono<Map<String, Object>> tradeTimeline(@AuthenticationPrincipal String userId) {
        return service.getTradeTimeline(UUID.fromString(userId));
    }

    @Operation(summary = "Switch broker account", description = "Switch which broker account is used for copy trading with a specific master. No need to unsubscribe.")
    @PutMapping(value = "/subscriptions/broker", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> switchBroker(@AuthenticationPrincipal String userId,
                                                   @RequestBody com.copytrading.child.dto.SwitchBrokerRequest req) {
        return service.switchBroker(UUID.fromString(userId), req.getMasterId(), req.getBrokerAccountId());
    }

    @Operation(summary = "Get live positions", description = "Fetch real open positions from child's active broker with live P&L")
    @GetMapping("/positions")
    public Mono<Map<String, Object>> getPositions(@AuthenticationPrincipal String userId) {
        return positionsService.getChildPositions(UUID.fromString(userId));
    }

    @GetMapping("/open-book")
    public Mono<Map<String, Object>> openBook(@AuthenticationPrincipal String userId) {
        return tradingDataService.getOpenBook(UUID.fromString(userId), false);
    }

    @GetMapping("/open-options")
    public Mono<Map<String, Object>> openOptions(@AuthenticationPrincipal String userId) {
        return tradingDataService.getOpenOptions(UUID.fromString(userId), false);
    }

    @GetMapping("/option-status")
    public Mono<Map<String, Object>> optionStatus(@AuthenticationPrincipal String userId,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to) {
        return tradingDataService.getOptionStatus(UUID.fromString(userId), false, from, to);
    }

    @Operation(summary = "Child P&L dashboard", description = "Alias for /analytics with live P&L")
    @GetMapping("/pnl-dashboard")
    public Mono<Map<String, Object>> pnlDashboard(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }
}