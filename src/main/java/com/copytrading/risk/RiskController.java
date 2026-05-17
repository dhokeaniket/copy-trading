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
                    m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
                    return m;
                });
    }

    @PutMapping("/rules")
    public Mono<Map<String, Object>> updateRiskRules(@AuthenticationPrincipal String userId,
                                                      @RequestBody Map<String, Object> body) {
        int maxTrades = body.containsKey("maxTradesPerDay") ? ((Number) body.get("maxTradesPerDay")).intValue() : 50;
        int maxPositions = body.containsKey("maxOpenPositions") ? ((Number) body.get("maxOpenPositions")).intValue() : 20;
        double maxExposure = body.containsKey("maxCapitalExposure") ? ((Number) body.get("maxCapitalExposure")).doubleValue() : 80;
        boolean marginCheck = body.containsKey("marginCheckEnabled") ? (Boolean) body.get("marginCheckEnabled") : true;

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
    public Mono<Map<String, Object>> checkRisk(@AuthenticationPrincipal String userId) {
        return riskService.checkRiskLimits(UUID.fromString(userId))
                .map(result -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("allowed", result.isEmpty());
                    m.put("reason", result.isEmpty() ? null : result);
                    return m;
                });
    }
}
