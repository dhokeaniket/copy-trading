package com.copytrading.master;

import com.copytrading.master.dto.BulkLinkRequest;
import com.copytrading.master.dto.LinkChildRequest;
import com.copytrading.master.dto.UpdateScalingRequest;
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
@RequestMapping("/api/v1/master")
@Tag(name = "3. Master", description = "Master trader: manage children, active account, analytics, earnings")
public class MasterController {

    private final MasterService service;
    private final PositionsService positionsService;
    private final TradingDataService tradingDataService;

    public MasterController(MasterService service, PositionsService positionsService,
                            TradingDataService tradingDataService) {
        this.service = service;
        this.positionsService = positionsService;
        this.tradingDataService = tradingDataService;
    }

    @Operation(summary = "List children", description = "List all children linked to this master")
    @GetMapping("/children")
    public Mono<Map<String, Object>> listChildren(@AuthenticationPrincipal String userId) {
        return service.listChildren(UUID.fromString(userId));
    }

    @PostMapping(value = "/children/{childId}/link", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> linkChild(@AuthenticationPrincipal String userId,
                                                @PathVariable UUID childId,
                                                @RequestBody(required = false) LinkChildRequest req) {
        return service.linkChild(UUID.fromString(userId), childId, req != null ? req.getScalingFactor() : null);
    }

    @PostMapping(value = "/children/bulk-link", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> bulkLinkChildren(@AuthenticationPrincipal String userId,
                                                       @RequestBody BulkLinkRequest req) {
        if (req == null || req.getChildren() == null) {
            return Mono.just(Map.of("error", "children list is required"));
        }
        try {
            var children = req.getChildren().stream().map(c -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                if (c.getChildId() == null) {
                    throw new IllegalArgumentException("childId cannot be null");
                }
                m.put("childId", c.getChildId().toString());
                if (c.getScalingFactor() != null) m.put("scalingFactor", c.getScalingFactor());
                return m;
            }).toList();
            return service.bulkLinkChildren(UUID.fromString(userId), children);
        } catch (IllegalArgumentException e) {
            return Mono.just(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid input format"));
        } catch (Exception e) {
            return Mono.just(Map.of("error", "Internal server error during bulk link: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/subscribe/{childId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> subscribeToChild(@AuthenticationPrincipal String userId,
                                                       @PathVariable UUID childId,
                                                       @RequestBody(required = false) LinkChildRequest req) {
        return service.subscribeToChild(UUID.fromString(userId), childId,
                req != null ? req.getScalingFactor() : null);
    }

    @DeleteMapping("/children/{childId}/unlink")
    public Mono<Map<String, String>> unlinkChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.unlinkChild(UUID.fromString(userId), childId);
    }

    @Operation(summary = "Remove child (alias)", description = "Same as unlink — sets subscription INACTIVE")
    @DeleteMapping("/children/{childId}")
    public Mono<Map<String, String>> removeChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.unlinkChild(UUID.fromString(userId), childId);
    }

    @PostMapping("/children/{childId}/remove")
    public Mono<Map<String, String>> removeChildPost(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.unlinkChild(UUID.fromString(userId), childId);
    }

    @PostMapping(value = "/children/bulk-unlink", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> bulkUnlinkChildren(@AuthenticationPrincipal String userId,
                                                         @RequestBody com.copytrading.master.dto.BulkUnlinkRequest req) {
        return service.bulkUnlinkChildren(UUID.fromString(userId), req.getChildIds());
    }

    @PostMapping("/children/{childId}/pause")
    public Mono<Map<String, String>> pauseChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.pauseChild(UUID.fromString(userId), childId);
    }

    @PostMapping("/children/{childId}/resume")
    public Mono<Map<String, String>> resumeChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.resumeChild(UUID.fromString(userId), childId);
    }

    @GetMapping("/children/pending")
    public Mono<Map<String, Object>> listPendingApprovals(@AuthenticationPrincipal String userId) {
        return service.listPendingApprovals(UUID.fromString(userId));
    }

    @PostMapping("/children/{childId}/approve")
    public Mono<Map<String, String>> approveChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.approveChild(UUID.fromString(userId), childId);
    }

    @PostMapping("/children/{childId}/reject")
    public Mono<Map<String, String>> rejectChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.rejectChild(UUID.fromString(userId), childId);
    }

    @PostMapping("/children/{childId}/decline")
    public Mono<Map<String, String>> declineChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.rejectChild(UUID.fromString(userId), childId);
    }

    @GetMapping("/children/{childId}/scaling")
    public Mono<Map<String, Object>> getScaling(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.getScaling(UUID.fromString(userId), childId);
    }

    @PutMapping(value = "/children/{childId}/scaling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateScaling(@AuthenticationPrincipal String userId,
                                                    @PathVariable UUID childId,
                                                    @RequestBody UpdateScalingRequest req) {
        return service.updateScaling(UUID.fromString(userId), childId, req.getScalingFactor());
    }

    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }

    @GetMapping("/dashboard")
    public Mono<Map<String, Object>> getDashboard(@AuthenticationPrincipal String userId) {
        return service.getDashboard(UUID.fromString(userId));
    }

    @Operation(summary = "Copy trading page", description = "Active master account, followers with live margin/P&L, alerts, polling")
    @GetMapping("/copy-trading")
    public Mono<Map<String, Object>> getCopyTradingPage(@AuthenticationPrincipal String userId) {
        return service.getCopyTradingPage(UUID.fromString(userId));
    }

    @Operation(summary = "P&L analytics", description = "Master + follower unrealized P&L, margins, daily copy chart")
    @GetMapping("/pnl-analytics")
    public Mono<Map<String, Object>> getPnlAnalytics(@AuthenticationPrincipal String userId) {
        return service.getPnlAnalytics(UUID.fromString(userId));
    }

    @GetMapping("/trade-history")
    public Mono<Map<String, Object>> getTradeHistory(@AuthenticationPrincipal String userId) {
        return service.getTradeHistory(UUID.fromString(userId));
    }

    @Operation(summary = "Trade logs (alias)", description = "Same data as trade-history — copy_logs backed")
    @GetMapping("/trade-logs")
    public Mono<Map<String, Object>> getTradeLogs(@AuthenticationPrincipal String userId) {
        return service.getTradeHistory(UUID.fromString(userId));
    }

    @Operation(summary = "Master trade P&L", description = "Spec §2.4 — master P&L summary with trades")
    @GetMapping("/trade-pnl")
    public Mono<Map<String, Object>> getTradePnl(@AuthenticationPrincipal String userId) {
        return service.getTradePnlSummary(UUID.fromString(userId));
    }

    // Active account
    @PostMapping(value = "/active-account", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> setActiveAccount(@AuthenticationPrincipal String userId,
                                                       @RequestBody com.copytrading.master.dto.ActiveAccountRequest body) {
        if (body == null || body.getBrokerAccountId() == null || body.getBrokerAccountId().isBlank()) {
            return Mono.just(Map.of("error", "brokerAccountId is required"));
        }
        try {
            return service.setActiveAccount(UUID.fromString(userId), UUID.fromString(body.getBrokerAccountId()));
        } catch (IllegalArgumentException e) {
            return Mono.just(Map.of("error", "Invalid UUID format"));
        }
    }

    // Bulk Active accounts
    @PostMapping(value = "/active-accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> setActiveAccounts(@AuthenticationPrincipal String userId,
                                                       @RequestBody com.copytrading.master.dto.ActiveAccountsRequest body) {
        if (body == null || body.getBrokerAccountIds() == null) {
            return Mono.just(Map.of("error", "brokerAccountIds is required"));
        }
        try {
            java.util.List<UUID> brokerIds = body.getBrokerAccountIds().stream()
                    .map(UUID::fromString)
                    .toList();
            return service.setActiveAccounts(UUID.fromString(userId), brokerIds);
        } catch (IllegalArgumentException e) {
            return Mono.just(Map.of("error", "Invalid UUID format in list"));
        }
    }

    @GetMapping("/active-account")
    public Mono<Map<String, Object>> getActiveAccount(@AuthenticationPrincipal String userId) {
        return service.getActiveAccount(UUID.fromString(userId));
    }

    @DeleteMapping("/active-account")
    public Mono<Map<String, String>> clearActiveAccount(@AuthenticationPrincipal String userId) {
        return service.clearActiveAccount(UUID.fromString(userId));
    }

    // Copy logs scoped to master
    @GetMapping("/copy/logs")
    public Mono<Map<String, Object>> getCopyLogs(@AuthenticationPrincipal String userId) {
        return service.getCopyLogs(UUID.fromString(userId));
    }

    // Earnings
    @GetMapping("/earnings")
    public Mono<Map<String, Object>> getEarnings(@AuthenticationPrincipal String userId) {
        return service.getEarnings(UUID.fromString(userId));
    }

    // Payouts
    @GetMapping("/payouts")
    public Mono<Map<String, Object>> getPayouts(@AuthenticationPrincipal String userId) {
        return service.getPayouts(UUID.fromString(userId));
    }

    @Operation(summary = "Get live positions", description = "Fetch real open positions from master's active broker with live P&L")
    @GetMapping("/positions")
    public Mono<Map<String, Object>> getPositions(@AuthenticationPrincipal String userId) {
        return positionsService.getMasterPositions(UUID.fromString(userId));
    }

    @Operation(summary = "Open order book", description = "Pending/open orders from master's active broker")
    @GetMapping("/open-book")
    public Mono<Map<String, Object>> openBook(@AuthenticationPrincipal String userId,
                                              @RequestParam(required = false) UUID accountId) {
        return tradingDataService.getOpenBook(UUID.fromString(userId), accountId, true);
    }

    @Operation(summary = "Open F&O positions", description = "Option/futures positions from master's active broker")
    @GetMapping("/open-options")
    public Mono<Map<String, Object>> openOptions(@AuthenticationPrincipal String userId) {
        return tradingDataService.getOpenOptions(UUID.fromString(userId), true);
    }

    @Operation(summary = "Option copy status", description = "F&O copy attempts with success/fail/skip details")
    @GetMapping("/option-status")
    public Mono<Map<String, Object>> optionStatus(@AuthenticationPrincipal String userId,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to) {
        return tradingDataService.getOptionStatus(UUID.fromString(userId), true, from, to);
    }

    @Operation(summary = "Square off position", description = "Close a position on master's active broker")
    @PostMapping(value = "/positions/square-off", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> squareOff(@AuthenticationPrincipal String userId,
                                              @RequestBody Map<String, Object> body) {
        return service.squareOffPosition(UUID.fromString(userId), body);
    }
}
