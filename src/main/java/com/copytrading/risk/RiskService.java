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
