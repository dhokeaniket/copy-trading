package com.copytrading.alert;

import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.notification.NotificationService;
import com.copytrading.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Checks child's broker balance and pushes notifications when balance is low.
 *
 * Thresholds:
 *   CRITICAL  — balance < ₹1,000   → "Balance bahut kam hai! Trade copy fail ho sakta hai"
 *   WARNING   — balance < ₹5,000   → "Balance kam hai, please add funds"
 *   LOW       — balance < ₹10,000  → "Balance low hai, keep an eye"
 */
@Service
public class BalanceAlertService {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertService.class);

    private static final double CRITICAL_THRESHOLD = 1000;
    private static final double WARNING_THRESHOLD = 5000;
    private static final double LOW_THRESHOLD = 10000;

    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;
    private final NotificationService notifications;
    private final SubscriptionRepository subs;

    public BalanceAlertService(BrokerAccountRepository brokerRepo,
                               BrokerAccountService brokerService,
                               NotificationService notifications,
                               SubscriptionRepository subs) {
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.notifications = notifications;
        this.subs = subs;
    }

    /**
     * Called after a trade is copied to a child. Checks their balance and alerts if low.
     */
    public Mono<Void> checkAndAlert(UUID childId, UUID brokerAccountId) {
        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .flatMap(a -> brokerService.getMargin(brokerAccountId, childId)
                        .flatMap(margin -> {
                            double available = toDouble(margin.getOrDefault("availableMargin", 0));
                            return evaluateAndNotify(childId, available, a.getBrokerId());
                        })
                        .onErrorResume(e -> {
                            log.warn("BALANCE_CHECK_FAILED child={} error={}", childId, e.getMessage());
                            return Mono.empty();
                        })
                )
                .then();
    }

    /**
     * Called by frontend to check balance alerts for a specific account.
     * Returns alert level and message.
     */
    public Mono<Map<String, Object>> checkBalance(UUID accountId, UUID userId) {
        return brokerService.getMargin(accountId, userId)
                .map(margin -> {
                    double available = toDouble(margin.getOrDefault("availableMargin", 0));
                    double total = toDouble(margin.getOrDefault("totalFunds", 0));
                    double used = toDouble(margin.getOrDefault("usedMargin", 0));

                    String alertLevel;
                    String message;
                    if (available < CRITICAL_THRESHOLD) {
                        alertLevel = "CRITICAL";
                        message = "Balance bahut kam hai! Trade copy fail ho sakta hai. Please add funds immediately.";
                    } else if (available < WARNING_THRESHOLD) {
                        alertLevel = "WARNING";
                        message = "Balance kam hai. Add funds to avoid trade copy failures.";
                    } else if (available < LOW_THRESHOLD) {
                        alertLevel = "LOW";
                        message = "Balance low hai, keep an eye on your funds.";
                    } else {
                        alertLevel = "OK";
                        message = "Balance sufficient.";
                    }

                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("alertLevel", alertLevel);
                    r.put("message", message);
                    r.put("availableMargin", available);
                    r.put("usedMargin", used);
                    r.put("totalFunds", total);
                    r.put("thresholds", Map.of(
                            "critical", CRITICAL_THRESHOLD,
                            "warning", WARNING_THRESHOLD,
                            "low", LOW_THRESHOLD
                    ));
                    return r;
                })
                .onErrorResume(e -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("alertLevel", "UNKNOWN");
                    r.put("message", "Could not check balance: " + e.getMessage());
                    return Mono.just(r);
                });
    }

    private Mono<Void> evaluateAndNotify(UUID childId, double available, String broker) {
        if (available < CRITICAL_THRESHOLD) {
            return notifications.push(childId,
                    "⚠️ Critical: Balance Very Low",
                    "Your " + broker + " account balance is ₹" + String.format("%.0f", available) +
                            ". Trade copy may fail! Please add funds immediately.",
                    "BALANCE_CRITICAL"
            ).then();
        } else if (available < WARNING_THRESHOLD) {
            return notifications.push(childId,
                    "⚠️ Warning: Low Balance",
                    "Your " + broker + " account balance is ₹" + String.format("%.0f", available) +
                            ". Add funds to avoid trade copy failures.",
                    "BALANCE_WARNING"
            ).then();
        } else if (available < LOW_THRESHOLD) {
            return notifications.push(childId,
                    "ℹ️ Balance Getting Low",
                    "Your " + broker + " account balance is ₹" + String.format("%.0f", available) +
                            ". Keep an eye on your funds.",
                    "BALANCE_LOW"
            ).then();
        }
        return Mono.empty();
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
