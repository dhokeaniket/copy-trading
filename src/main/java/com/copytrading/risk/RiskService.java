package com.copytrading.risk;

import com.copytrading.broker.BrokerAccountService;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.trade.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Risk control service — enforces per-child risk limits before allowing copy trades.
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskRuleRepository riskRepo;
    private final CopyLogRepository copyLogs;
    private final TradeRepository trades;
    private final BrokerAccountService brokerService;

    public RiskService(RiskRuleRepository riskRepo, CopyLogRepository copyLogs, TradeRepository trades,
                       BrokerAccountService brokerService) {
        this.riskRepo = riskRepo;
        this.copyLogs = copyLogs;
        this.trades = trades;
        this.brokerService = brokerService;
    }

    public Mono<String> checkRiskLimits(UUID childId) {
        return checkRiskLimits(childId, null);
    }

    /**
     * Check if a child is allowed to take a new copy trade.
     * Returns empty string if allowed, or a rejection reason if blocked.
     */
    public Mono<String> checkRiskLimits(UUID childId, UUID brokerAccountId) {
        return riskRepo.findByUserId(childId)
                .flatMap(rule -> {
                    if (rule.isCopyPaused()) {
                        return Mono.just("COPY_PAUSED: Copy trading is paused");
                    }
                    if (rule.getPausedUntil() != null && Instant.now().isBefore(rule.getPausedUntil())) {
                        return Mono.just("COPY_PAUSED: Paused until " + rule.getPausedUntil());
                    }
                    Instant todayStart = Instant.now().minus(Duration.ofHours(12));
                    return copyLogs.findByChildId(childId)
                            .filter(cl -> cl.getCreatedAt() != null && cl.getCreatedAt().isAfter(todayStart))
                            .filter(cl -> "SUCCESS".equals(cl.getChildStatus()))
                            .count()
                            .flatMap(todayTrades -> {
                                if (todayTrades >= rule.getMaxTradesPerDay()) {
                                    log.info("RISK_BLOCKED child={} reason=MAX_TRADES_PER_DAY limit={} current={}",
                                            childId, rule.getMaxTradesPerDay(), todayTrades);
                                    return Mono.just("MAX_TRADES_PER_DAY: Limit " + rule.getMaxTradesPerDay()
                                            + " reached (" + todayTrades + " today)");
                                }
                                return trades.findByUserIdAndStatus(childId, "EXECUTED")
                                        .count()
                                        .flatMap(openPositions -> {
                                            if (openPositions >= rule.getMaxOpenPositions()) {
                                                log.info("RISK_BLOCKED child={} reason=MAX_OPEN_POSITIONS limit={} current={}",
                                                        childId, rule.getMaxOpenPositions(), openPositions);
                                                return Mono.just("MAX_OPEN_POSITIONS: Limit " + rule.getMaxOpenPositions()
                                                        + " reached (" + openPositions + " open)");
                                            }
                                            return checkMarginUtilization(childId, brokerAccountId, rule);
                                        });
                            });
                })
                .defaultIfEmpty("");
    }

    private Mono<String> checkMarginUtilization(UUID childId, UUID brokerAccountId, RiskRule rule) {
        if (!rule.isMarginCheckEnabled() || brokerAccountId == null) {
            return Mono.just("");
        }
        return brokerService.getMargin(brokerAccountId, childId)
                .map(margin -> marginUtilizationMessage(margin, rule.getMaxCapitalExposure()))
                .onErrorResume(e -> {
                    log.warn("RISK_MARGIN_CHECK_SKIP child={} err={}", childId, e.getMessage());
                    return Mono.just("");
                });
    }

    static String marginUtilizationMessage(Map<String, Object> margin, double maxExposurePct) {
        double total = toDouble(margin.getOrDefault("totalFunds", 0));
        double available = toDouble(margin.getOrDefault("availableMargin", 0));
        if (total <= 0) return "";
        double usedPct = ((total - available) / total) * 100.0;
        if (usedPct >= maxExposurePct) {
            return "MAX_CAPITAL_EXPOSURE: Margin utilization " + String.format("%.1f", usedPct)
                    + "% exceeds limit " + maxExposurePct + "%";
        }
        return "";
    }

    private static double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Live risk dashboard for UI: limits, today's usage, margin utilization %.
     */
    public Mono<Map<String, Object>> getRiskStatus(UUID childId, UUID brokerAccountId) {
        return getRiskRules(childId).flatMap(rule -> {
            Instant todayStart = Instant.now().minus(Duration.ofHours(12));
            Mono<Long> todayTradesMono = copyLogs.findByChildId(childId)
                    .filter(cl -> cl.getCreatedAt() != null && cl.getCreatedAt().isAfter(todayStart))
                    .filter(cl -> "SUCCESS".equals(cl.getChildStatus()))
                    .count();
            Mono<Long> openPositionsMono = trades.findByUserIdAndStatus(childId, "EXECUTED").count();
            Mono<Map<String, Object>> marginMono = brokerAccountId != null
                    ? brokerService.getMargin(brokerAccountId, childId)
                            .onErrorReturn(Map.of("availableMargin", 0, "totalFunds", 0, "usedMargin", 0))
                    : Mono.just(Map.of("availableMargin", 0, "totalFunds", 0, "usedMargin", 0));

            return Mono.zip(todayTradesMono, openPositionsMono, marginMono)
                    .map(t -> {
                        long todayTrades = t.getT1();
                        long openPositions = t.getT2();
                        Map<String, Object> margin = t.getT3();
                        double total = toDouble(margin.getOrDefault("totalFunds", 0));
                        double available = toDouble(margin.getOrDefault("availableMargin", 0));
                        double usedPct = total > 0 ? ((total - available) / total) * 100.0 : 0;

                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("maxTradesPerDay", rule.getMaxTradesPerDay());
                        m.put("tradesToday", todayTrades);
                        m.put("tradesRemaining", Math.max(0, rule.getMaxTradesPerDay() - todayTrades));
                        m.put("maxOpenPositions", rule.getMaxOpenPositions());
                        m.put("openPositions", openPositions);
                        m.put("positionsRemaining", Math.max(0, rule.getMaxOpenPositions() - openPositions));
                        m.put("maxCapitalExposure", rule.getMaxCapitalExposure());
                        m.put("marginCheckEnabled", rule.isMarginCheckEnabled());
                        m.put("marginUtilizationPct", Math.round(usedPct * 10) / 10.0);
                        m.put("marginBlocked", rule.isMarginCheckEnabled() && usedPct >= rule.getMaxCapitalExposure());
                        m.put("availableMargin", available);
                        m.put("usedMargin", total - available);
                        m.put("totalFunds", total);
                        m.put("copyPaused", rule.isCopyPaused());
                        m.put("pausedUntil", rule.getPausedUntil());
                        m.put("allowed", todayTrades < rule.getMaxTradesPerDay()
                                && openPositions < rule.getMaxOpenPositions()
                                && (!rule.isMarginCheckEnabled() || usedPct < rule.getMaxCapitalExposure()));
                        return m;
                    });
        });
    }

    public Mono<Map<String, Object>> getExposure(UUID childId, UUID brokerAccountId) {
        return getRiskStatus(childId, brokerAccountId)
                .map(status -> {
                    Map<String, Object> e = new java.util.LinkedHashMap<>();
                    e.put("totalCapital", status.getOrDefault("totalFunds", 0));
                    e.put("deployedCapital", status.getOrDefault("usedMargin", 0));
                    e.put("exposurePercent", status.getOrDefault("marginUtilizationPct", 0));
                    e.put("openPositions", status.getOrDefault("openPositions", 0));
                    e.put("tradesPlacedToday", status.getOrDefault("tradesToday", 0));
                    e.put("marginAvailable", status.getOrDefault("availableMargin", 0));
                    return e;
                });
    }

    public Mono<Map<String, Object>> checkTrade(UUID childId, UUID brokerAccountId, Map<String, Object> trade) {
        return checkRiskLimits(childId, brokerAccountId)
                .map(reason -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    boolean allowed = reason == null || reason.isEmpty();
                    r.put("allowed", allowed);
                    r.put("warnings", List.of());
                    r.put("checks", List.of(Map.of(
                            "rule", "composite",
                            "status", allowed ? "OK" : "BLOCKED",
                            "message", allowed ? "OK" : reason
                    )));
                    r.put("symbol", trade.get("symbol"));
                    return r;
                });
    }

    public Mono<Map<String, Object>> pauseCopy(UUID childId, String reason, Instant pauseUntil) {
        return riskRepo.findByUserId(childId)
                .switchIfEmpty(Mono.defer(() -> {
                    RiskRule r = defaultRules(childId);
                    return riskRepo.save(r);
                }))
                .flatMap(rule -> {
                    rule.setCopyPaused(true);
                    rule.setPausedUntil(pauseUntil);
                    rule.setUpdatedAt(Instant.now());
                    return riskRepo.save(rule);
                })
                .map(rule -> Map.of(
                        "paused", true,
                        "pausedAt", Instant.now().toString(),
                        "pausedUntil", pauseUntil != null ? pauseUntil.toString() : null,
                        "reason", reason != null ? reason : "Manual pause"
                ));
    }

    public Mono<Map<String, Object>> resumeCopy(UUID childId) {
        return riskRepo.findByUserId(childId)
                .flatMap(rule -> {
                    rule.setCopyPaused(false);
                    rule.setPausedUntil(null);
                    rule.setUpdatedAt(Instant.now());
                    return riskRepo.save(rule);
                })
                .map(rule -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("paused", false);
                    r.put("message", "Copy trading resumed");
                    return r;
                })
                .defaultIfEmpty(Map.of("paused", false, "message", "No risk rules found"));
    }

    public Mono<RiskRule> getRiskRules(UUID userId) {
        return riskRepo.findByUserId(userId)
                .switchIfEmpty(Mono.just(defaultRules(userId)));
    }

    public Mono<RiskRule> saveRiskRules(UUID userId, int maxTradesPerDay, int maxOpenPositions,
                                         double maxCapitalExposure, boolean marginCheckEnabled) {
        return riskRepo.findByUserId(userId)
                .flatMap(existing -> {
                    existing.setMaxTradesPerDay(maxTradesPerDay);
                    existing.setMaxOpenPositions(maxOpenPositions);
                    existing.setMaxCapitalExposure(maxCapitalExposure);
                    existing.setMarginCheckEnabled(marginCheckEnabled);
                    existing.setUpdatedAt(Instant.now());
                    return riskRepo.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    RiskRule r = new RiskRule();
                    r.setUserId(userId);
                    r.setMaxTradesPerDay(maxTradesPerDay);
                    r.setMaxOpenPositions(maxOpenPositions);
                    r.setMaxCapitalExposure(maxCapitalExposure);
                    r.setMarginCheckEnabled(marginCheckEnabled);
                    r.setUpdatedAt(Instant.now());
                    return riskRepo.save(r);
                }));
    }

    private RiskRule defaultRules(UUID userId) {
        RiskRule r = new RiskRule();
        r.setUserId(userId);
        r.setMaxTradesPerDay(50);
        r.setMaxOpenPositions(20);
        r.setMaxCapitalExposure(80);
        r.setMarginCheckEnabled(true);
        return r;
    }
}
