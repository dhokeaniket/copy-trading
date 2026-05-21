package com.copytrading.notification;

import com.copytrading.auth.UserAccount;
import com.copytrading.auth.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Spec §6 — Telegram link codes and preferences. */
@Service
public class TelegramLinkService {

    private final UserAccountRepository userRepo;
    private final TelegramService telegram;
    private final String botUsername;
    private final ConcurrentHashMap<String, LinkCode> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Object>> preferences = new ConcurrentHashMap<>();

    public TelegramLinkService(UserAccountRepository userRepo, TelegramService telegram,
                               @Value("${telegram.bot-username:AscentraAlertBot}") String botUsername) {
        this.userRepo = userRepo;
        this.telegram = telegram;
        this.botUsername = botUsername;
    }

    public Mono<Map<String, Object>> generateLinkToken(UUID userId) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));
        codes.put(code, new LinkCode(userId, Instant.now().plusSeconds(600)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("expiresAt", codes.get(code).expiresAt.toString());
        r.put("botUsername", botUsername);
        r.put("instruction", "Send /link " + code + " to @" + botUsername + " on Telegram");
        return Mono.just(r);
    }

    public Mono<Map<String, Object>> linkByCode(String code, String chatId) {
        LinkCode lc = codes.remove(code);
        if (lc == null || lc.expiresAt.isBefore(Instant.now())) {
            return telegram.sendMessage(chatId, "❌ Invalid or expired link code.")
                    .thenReturn(Map.of("linked", false));
        }
        return userRepo.findById(lc.userId)
                .flatMap(u -> {
                    u.setTelegramChatId(chatId);
                    return userRepo.save(u);
                })
                .flatMap(u -> telegram.sendMessage(chatId, "✅ Your Ascentra account is now linked!")
                        .thenReturn(Map.<String, Object>of("linked", true, "userId", u.getId().toString())));
    }

    public Mono<Map<String, Object>> getStatus(UUID userId) {
        return userRepo.findById(userId).map(u -> {
            Map<String, Object> r = new LinkedHashMap<>();
            boolean linked = u.getTelegramChatId() != null && !u.getTelegramChatId().isBlank();
            r.put("linked", linked);
            r.put("chatId", linked ? u.getTelegramChatId() : null);
            r.put("preferences", preferences.getOrDefault(userId, defaultPreferences()));
            return r;
        });
    }

    public Mono<Map<String, Object>> updatePreferences(UUID userId, Map<String, Object> prefs) {
        preferences.put(userId, new LinkedHashMap<>(prefs));
        return getStatus(userId);
    }

    public Mono<Map<String, Object>> unlink(UUID userId) {
        return userRepo.findById(userId)
                .flatMap(u -> {
                    u.setTelegramChatId(null);
                    return userRepo.save(u);
                })
                .thenReturn(Map.of("linked", false, "message", "Telegram unlinked"));
    }

    public Mono<Map<String, Object>> sendTest(UUID userId) {
        return telegram.sendToUser(userId, "🟢 <b>Ascentra Test</b>\nTelegram alerts are working.")
                .thenReturn(Map.<String, Object>of("sent", true))
                .onErrorResume(e -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("sent", false);
                    err.put("error", e.getMessage());
                    return Mono.just(err);
                });
    }

    public Mono<String> handleWebhookMessage(String text, String chatId) {
        if (text == null) return Mono.just("OK");
        String trimmed = text.trim();
        if (trimmed.startsWith("/link")) {
            String code = trimmed.replace("/link", "").trim();
            return linkByCode(code, chatId).thenReturn("OK");
        }
        if ("/help".equalsIgnoreCase(trimmed)) {
            return telegram.sendMessage(chatId,
                    "<b>Commands</b>\n/link CODE — link account\n/status — trading status\n/help — this message")
                    .thenReturn("OK");
        }
        if ("/status".equalsIgnoreCase(trimmed)) {
            return userRepo.findAll()
                    .filter(u -> chatId.equals(u.getTelegramChatId()))
                    .next()
                    .flatMap(u -> telegram.sendMessage(chatId, "Account: " + u.getName() + " (" + u.getRole() + ")"))
                    .switchIfEmpty(telegram.sendMessage(chatId, "No linked account. Use /link CODE from the app."))
                    .thenReturn("OK");
        }
        return Mono.just("OK");
    }

    private static Map<String, Object> defaultPreferences() {
        return Map.of(
                "tradeAlerts", true,
                "riskAlerts", true,
                "dailySummary", true,
                "systemAlerts", false,
                "alertOnSuccess", true,
                "alertOnFailure", true,
                "alertOnSkipped", true
        );
    }

    private record LinkCode(UUID userId, Instant expiresAt) {}
}
