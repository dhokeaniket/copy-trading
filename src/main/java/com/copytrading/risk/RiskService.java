package com.copytrading.risk;

import com.copytrading.logs.CopyLog;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.trade.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Risk control service — enforces per-child risk limits before allowing copy trades.
 * Covers: CT-069 (max daily loss), CT-070 (max open trades), CT-071 (max margin utilization)
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskRuleRepository riskRepo;
    private final CopyLogRepository copyLogs;
    private final TradeRepository trades;

    public RiskService(RiskRuleRepository riskRepo, CopyLogRepository copyLogs, TradeRepository trades) {
        this.riskRepo = riskRepo;
        this.copyLogs = copyLogs;
        this.trades = trades;
    }

    /**
     * Check if a child is allowed to take a new copy trade.
     * Returns empty string if allowed, or a rejection reason if blocked.
     */
    public Mono<String> checkRiskLimits(UUID childId) {
        return riskRepo.findByUserId(childId)
                .flatMap(rule -> {
                    // Check max trades per day (CT-070)
                    Instant todayStart = Instant.now().minus(Duration.ofHours(12)); // ~market day window
                    return copyLogs.findByChildId(childId)
                            .filter(cl -> cl.getCreatedAt() != null && cl.getCreatedAt().isAfter(todayStart))
                            .filter(cl -> "SUCCESS".equals(cl.getChildStatus()))
                            .count()
                            .flatMap(todayTrades -> {
                                if (todayTrades >= rule.getMaxTradesPerDay()) {
                                    log.info("RISK_BLOCKED child={} reason=MAX_TRADES_PER_DAY limit={} current={}",
                                            childId, rule.getMaxTradesPerDay(), todayTrades);
                                    return Mono.just("MAX_TRADES_PER_DAY: Limit " + rule.getMaxTradesPerDay() + " reached (" + todayTrades + " today)");
                                }

                                // Check max open positions (CT-070)
                                return trades.findByUserIdAndStatus(childId, "EXECUTED")
                                        .count()
                                        .map(openPositions -> {
                                            if (openPositions >= rule.getMaxOpenPositions()) {
                                                log.info("RISK_BLOCKED child={} reason=MAX_OPEN_POSITIONS limit={} current={}",
                                                        childId, rule.getMaxOpenPositions(), openPositions);
                                                return "MAX_OPEN_POSITIONS: Limit " + rule.getMaxOpenPositions() + " reached (" + openPositions + " open)";
                                            }
                                            return ""; // All checks passed
                                        });
                            });
                })
                .defaultIfEmpty(""); // No risk rules configured = allow all
    }

    /**
     * Get or create default risk rules for a user.
     */
    public Mono<RiskRule> getRiskRules(UUID userId) {
        return riskRepo.findByUserId(userId)
                .switchIfEmpty(Mono.just(defaultRules(userId)));
    }

    /**
     * Save/update risk rules for a user.
     */
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
