package com.copytrading.risk;

import com.copytrading.broker.BrokerAccountService;
import com.copytrading.trade.TradeRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@RestController
public class RiskController {

    private final RiskRuleRepository rules;
    private final TradeRepository trades;
    private final BrokerAccountService brokerService;

    public RiskController(RiskRuleRepository rules, TradeRepository trades, BrokerAccountService brokerService) {
        this.rules = rules;
        this.trades = trades;
        this.brokerService = brokerService;
    }

    /** 7.1 GET /risk/rules */
    @GetMapping("/api/v1/risk/rules")
    public Mono<Map<String, Object>> getRules(@AuthenticationPrincipal String userId) {
        return rules.findById(UUID.fromString(userId))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("maxTradesPerDay", r.getMaxTradesPerDay());
                    m.put("maxOpenPositions", r.getMaxOpenPositions());
                    m.put("maxCapitalExposure", r.getMaxCapitalExposure());
                    m.put("marginCheckEnabled", r.isMarginCheckEnabled());
                    return m;
                })
                .defaultIfEmpty(Map.of("maxTradesPerDay", 50, "maxOpenPositions", 20,
                        "maxCapitalExposure", 80.0, "marginCheckEnabled", true));
    }

    /** 7.2 PUT /admin/risk/rules/:userId */
    @PutMapping(value = "/api/v1/admin/risk/rules/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> setRules(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
        return rules.findById(userId)
                .defaultIfEmpty(newRule(userId))
                .flatMap(r -> {
                    if (body.containsKey("maxTradesPerDay")) r.setMaxTradesPerDay(((Number) body.get("maxTradesPerDay")).intValue());
                    if (body.containsKey("maxOpenPositions")) r.setMaxOpenPositions(((Number) body.get("maxOpenPositions")).intValue());
                    if (body.containsKey("maxCapitalExposure")) r.setMaxCapitalExposure(((Number) body.get("maxCapitalExposure")).doubleValue());
                    if (body.containsKey("marginCheckEnabled")) r.setMarginCheckEnabled((Boolean) body.get("marginCheckEnabled"));
                    r.setUpdatedAt(Instant.now());
                    return rules.save(r);
                })
                .thenReturn(Map.of("message", "Risk rules updated"));
    }

    /** 7.3 GET /risk/exposure */
    @GetMapping("/api/v1/risk/exposure")
    public Mono<Map<String, Object>> getExposure(@AuthenticationPrincipal String userId) {
        UUID uid = UUID.fromString(userId);
        return Mono.zip(
                trades.countTodayTrades(uid).defaultIfEmpty(0L),
                trades.findByUserIdAndStatus(uid, "EXECUTED").collectList(),
                rules.findById(uid).defaultIfEmpty(newRule(uid))
        ).map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("currentOpenPositions", t.getT2().size());
            r.put("maxOpenPositions", t.getT3().getMaxOpenPositions());
            r.put("tradesToday", t.getT1());
            r.put("maxTradesPerDay", t.getT3().getMaxTradesPerDay());
            r.put("capitalExposurePct", 0); // Would need real margin data
            return r;
        });
    }

    /** 7.4 GET /risk/margin-check */
    @GetMapping("/api/v1/risk/margin-check")
    public Mono<Map<String, Object>> marginCheck(@AuthenticationPrincipal String userId,
                                                  @RequestParam UUID brokerAccountId,
                                                  @RequestParam String instrument,
                                                  @RequestParam int quantity,
                                                  @RequestParam String orderType,
                                                  @RequestParam(required = false, defaultValue = "0") double price) {
        return brokerService.getMargin(brokerAccountId, UUID.fromString(userId))
                .map(margin -> {
                    double available = toDouble(margin.getOrDefault("availableMargin", 0));
                    double required = price > 0 ? price * quantity : quantity * 100; // rough estimate
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("sufficient", available >= required);
                    r.put("requiredMargin", required);
                    r.put("availableMargin", available);
                    r.put("shortfall", Math.max(0, required - available));
                    return r;
                });
    }

    private RiskRule newRule(UUID userId) {
        RiskRule r = new RiskRule();
        r.setUserId(userId);
        return r;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
