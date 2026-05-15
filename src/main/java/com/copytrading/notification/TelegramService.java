package com.copytrading.notification;

import com.copytrading.auth.UserAccount;
import com.copytrading.auth.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Sends Telegram notifications to users who have linked their Telegram chat ID.
 * Uses the Telegram Bot API: https://api.telegram.org/bot{token}/sendMessage
 */
@Service
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);

    private final WebClient webClient;
    private final UserAccountRepository userRepo;
    private final String botToken;
    private final boolean enabled;

    public TelegramService(UserAccountRepository userRepo,
                           @Value("${telegram.bot-token:}") String botToken,
                           @Value("${telegram.enabled:false}") boolean enabled) {
        this.userRepo = userRepo;
        this.botToken = botToken;
        this.enabled = enabled;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.telegram.org")
                .build();
    }

    /**
     * Send a Telegram message to a user if they have a chat ID configured.
     */
    public Mono<Void> sendToUser(UUID userId, String message) {
        if (!enabled || botToken == null || botToken.isBlank()) {
            return Mono.empty();
        }
        return userRepo.findById(userId)
                .flatMap(user -> {
                    String chatId = user.getTelegramChatId();
                    if (chatId == null || chatId.isBlank()) {
                        return Mono.empty();
                    }
                    return sendMessage(chatId, message);
                });
    }

    /**
     * Send a Telegram message directly to a chat ID.
     */
    public Mono<Void> sendMessage(String chatId, String message) {
        if (!enabled || botToken == null || botToken.isBlank()) {
            return Mono.empty();
        }
        return webClient.post()
                .uri("/bot{token}/sendMessage", botToken)
                .bodyValue(Map.of(
                        "chat_id", chatId,
                        "text", message,
                        "parse_mode", "HTML"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .doOnSuccess(r -> log.debug("TELEGRAM_SENT chatId={}", chatId))
                .doOnError(e -> log.warn("TELEGRAM_FAILED chatId={} error={}", chatId, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * Send trade notification with formatted message.
     */
    public Mono<Void> notifyTrade(UUID userId, String side, String symbol, int qty, String status, String detail) {
        String emoji = "BUY".equalsIgnoreCase(side) ? "🟢" : "🔴";
        String statusEmoji = switch (status.toUpperCase()) {
            case "SUCCESS" -> "✅";
            case "FAILED" -> "❌";
            case "SKIPPED" -> "⏭️";
            case "REJECTED" -> "🚫";
            default -> "ℹ️";
        };

        String msg = statusEmoji + " <b>Trade " + status + "</b>\n"
                + emoji + " " + side + " " + symbol + " ×" + qty + "\n"
                + (detail != null ? "📝 " + detail : "");

        return sendToUser(userId, msg.trim());
    }
}
