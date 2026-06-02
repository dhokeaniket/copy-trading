package com.copytrading.risk;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/rules")
    public Mono<Map<String, Object>> getRiskRules(@AuthenticationPrincipal String userId) {
        return riskService.getRiskRules(UUID.fromString(userId))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", r.getUserId() != null ? r.getUserId().toString() : userId);
                    m.put("maxTradesPerDay", r.getMaxTradesPerDay());
                    m.put("maxOpenPositions", r.getMaxOpenPositions());
                    m.put("maxCapitalExposure", r.getMaxCapitalExposure());
                    m.put("marginCheckEnabled", r.isMarginCheckEnabled());
                    m.put("copyPaused", r.isCopyPaused());
                    m.put("pausedUntil", r.getPausedUntil() != null ? r.getPausedUntil().toString() : null);
                    m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
                    return m;
                });
    }

    private static boolean parseBoolean(Object val, boolean defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(val));
    }

    @PutMapping("/rules")
    public Mono<Map<String, Object>> updateRiskRules(@AuthenticationPrincipal String userId,
                                                      @RequestBody Map<String, Object> body) {
        int maxTrades = body.containsKey("maxTradesPerDay") ? ((Number) body.get("maxTradesPerDay")).intValue() : 50;
        int maxPositions = body.containsKey("maxOpenPositions") ? ((Number) body.get("maxOpenPositions")).intValue() : 20;
        double maxExposure = body.containsKey("maxCapitalExposure") ? ((Number) body.get("maxCapitalExposure")).doubleValue() : 80;
        boolean marginCheck = parseBoolean(body.get("marginCheckEnabled"), true);

        return riskService.saveRiskRules(UUID.fromString(userId), maxTrades, maxPositions, maxExposure, marginCheck)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("message", "Risk rules updated");
                    m.put("maxTradesPerDay", r.getMaxTradesPerDay());
                    m.put("maxOpenPositions", r.getMaxOpenPositions());
                    m.put("maxCapitalExposure", r.getMaxCapitalExposure());
                    m.put("marginCheckEnabled", r.isMarginCheckEnabled());
                    return m;
                });
    }

    @GetMapping("/check")
    public Mono<Map<String, Object>> checkRisk(@AuthenticationPrincipal String userId,
                                                @RequestParam(required = false) UUID brokerAccountId) {
        UUID childId = UUID.fromString(userId);
        Mono<String> checkMono = brokerAccountId != null
                ? riskService.checkRiskLimits(childId, brokerAccountId)
                : riskService.checkRiskLimits(childId);
        return checkMono.map(result -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allowed", result.isEmpty());
            m.put("reason", result.isEmpty() ? null : result);
            return m;
        });
    }

    /** Live risk dashboard for child UI (limits vs current usage + margin %). */
    @GetMapping("/status")
    public Mono<Map<String, Object>> getRiskStatus(@AuthenticationPrincipal String userId,
                                                     @RequestParam(required = false) UUID brokerAccountId) {
        return riskService.getRiskStatus(UUID.fromString(userId), brokerAccountId);
    }

    @GetMapping("/exposure")
    public Mono<Map<String, Object>> getExposure(@AuthenticationPrincipal String userId,
                                                  @RequestParam(required = false) UUID brokerAccountId) {
        return riskService.getExposure(UUID.fromString(userId), brokerAccountId);
    }

    @PostMapping(value = "/check-trade", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> checkTrade(@AuthenticationPrincipal String userId,
                                                 @RequestParam(required = false) UUID brokerAccountId,
                                                 @RequestBody Map<String, Object> body) {
        return riskService.checkTrade(UUID.fromString(userId), brokerAccountId, body);
    }

    @PostMapping(value = "/pause", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> pause(@AuthenticationPrincipal String userId,
                                            @RequestBody Map<String, Object> body) {
        String reason = body.containsKey("reason") ? String.valueOf(body.get("reason")) : "Manual pause";
        java.time.Instant until = null;
        if (body.containsKey("pauseUntil")) {
            try { until = java.time.Instant.parse(String.valueOf(body.get("pauseUntil"))); } catch (Exception ignored) {}
        }
        return riskService.pauseCopy(UUID.fromString(userId), reason, until);
    }

    @PostMapping("/resume")
    public Mono<Map<String, Object>> resume(@AuthenticationPrincipal String userId) {
        return riskService.resumeCopy(UUID.fromString(userId));
    }
}
