package com.copytrading.broker;

import com.copytrading.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Alerts users when broker sessions expire within 30 minutes. */
@Component
public class BrokerSessionExpiryMonitor {

    private static final Logger log = LoggerFactory.getLogger(BrokerSessionExpiryMonitor.class);

    private final BrokerAccountRepository brokerRepo;
    private final NotificationService notifications;

    public BrokerSessionExpiryMonitor(BrokerAccountRepository brokerRepo,
                                      NotificationService notifications) {
        this.brokerRepo = brokerRepo;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void checkExpiringSessions() {
        Instant threshold = Instant.now().plus(30, ChronoUnit.MINUTES);
        brokerRepo.findAll()
                .filter(a -> a.isSessionActive() && a.getSessionExpires() != null)
                .filter(a -> a.getSessionExpires().isBefore(threshold) && a.getSessionExpires().isAfter(Instant.now()))
                .flatMap(a -> notifyUser(a.getUserId(), a.getBrokerId()))
                .subscribe();
    }

    private Mono<Void> notifyUser(java.util.UUID userId, String brokerId) {
        String name = switch (brokerId != null ? brokerId : "") {
            case "GROWW" -> "Groww";
            case "ZERODHA" -> "Zerodha";
            case "FYERS" -> "Fyers";
            case "UPSTOX" -> "Upstox";
            case "DHAN" -> "Dhan";
            case "ANGELONE" -> "Angel One";
            default -> brokerId;
        };
        return notifications.push(userId, "Broker session expiring soon",
                        "Your " + name + " session expires within 30 minutes. Re-login to avoid missed copies.",
                        "SESSION_EXPIRING")
                .then();
    }
}
