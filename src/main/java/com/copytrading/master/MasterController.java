package com.copytrading.master;

import com.copytrading.master.dto.BulkLinkRequest;
import com.copytrading.master.dto.LinkChildRequest;
import com.copytrading.master.dto.UpdateScalingRequest;
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

    public MasterController(MasterService service) { this.service = service; }

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
        var children = req.getChildren().stream().map(c -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("childId", c.getChildId().toString());
            if (c.getScalingFactor() != null) m.put("scalingFactor", c.getScalingFactor());
            return m;
        }).toList();
        return service.bulkLinkChildren(UUID.fromString(userId), children);
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

    @PostMapping(value = "/children/bulk-unlink", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> bulkUnlinkChildren(@AuthenticationPrincipal String userId,
                                                         @RequestBody Map<String, java.util.List<UUID>> req) {
        return service.bulkUnlinkChildren(UUID.fromString(userId), req.get("childIds"));
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

    @GetMapping("/trade-history")
    public Mono<Map<String, Object>> getTradeHistory(@AuthenticationPrincipal String userId) {
        return service.getTradeHistory(UUID.fromString(userId));
    }

    // Active account
    @PostMapping(value = "/active-account", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> setActiveAccount(@AuthenticationPrincipal String userId,
                                                       @RequestBody Map<String, String> body) {
        return service.setActiveAccount(UUID.fromString(userId), UUID.fromString(body.get("brokerAccountId")));
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
}
