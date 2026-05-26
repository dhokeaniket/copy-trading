package com.copytrading.notification;

import com.copytrading.auth.UserAccount;
import com.copytrading.auth.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Telegram account linking via /link CODE and trade alerts. */
@Service
public class TelegramLinkService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkService.class);

    private final UserAccountRepository userRepo;
    private final TelegramService telegram;
    private final String botUsername;
    private final ConcurrentHashMap<String, LinkCode> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Object>> preferences = new ConcurrentHashMap<>();

    public TelegramLinkService(UserAccountRepository userRepo, TelegramService telegram,
                               @Value("${telegram.bot-username:Copy_tradingsBot}") String botUsername) {
        this.userRepo = userRepo;
        this.telegram = telegram;
        this.botUsername = normalizeUsername(botUsername);
    }

    public Mono<Map<String, Object>> generateLinkToken(UUID userId) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));
        codes.put(code, new LinkCode(userId, Instant.now().plusSeconds(600)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("expiresAt", codes.get(code).expiresAt.toString());
        r.put("botUsername", botUsername);
        r.put("botLink", "https://t.me/" + botUsername);
        r.put("instruction", "Open @" + botUsername + " in Telegram and send: /link " + code);
        r.put("deepLink", "https://t.me/" + botUsername + "?start=link_" + code);
        return Mono.just(r);
    }

    public Mono<Map<String, Object>> linkByCode(String code, String chatId) {
        if (code == null || code.isBlank()) {
            return telegram.sendMessage(chatId, "❌ Send: <code>/link 123456</code> (6-digit code from the app).")
                    .thenReturn(Map.of("linked", false));
        }
        String normalized = code.replaceAll("\\s+", "").trim();
        LinkCode lc = codes.remove(normalized);
        if (lc == null || lc.expiresAt.isBefore(Instant.now())) {
            return telegram.sendMessage(chatId, "❌ Invalid or expired link code. Generate a new code in the Ascentra app (Profile → Telegram).")
                    .thenReturn(Map.of("linked", false));
        }
        return userRepo.findById(lc.userId)
                .flatMap(u -> {
                    u.setTelegramChatId(chatId);
                    return userRepo.save(u);
                })
                .flatMap(u -> telegram.sendMessage(chatId,
                        "✅ <b>Linked to Ascentra</b>\nAccount: " + u.getName() + "\nYou will receive copy-trade alerts here.")
                        .thenReturn(Map.<String, Object>of("linked", true, "userId", u.getId().toString())));
    }

    public Mono<Map<String, Object>> getStatus(UUID userId) {
        return userRepo.findById(userId).map(u -> {
            Map<String, Object> r = new LinkedHashMap<>();
            boolean linked = u.getTelegramChatId() != null && !u.getTelegramChatId().isBlank();
            r.put("linked", linked);
            r.put("chatId", linked ? u.getTelegramChatId() : null);
            r.put("botUsername", botUsername);
            r.put("botLink", "https://t.me/" + botUsername);
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
        return telegram.sendToUser(userId, "🟢 <b>Ascentra Test</b>\nTelegram alerts are working for @" + botUsername + ".")
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

        if (trimmed.startsWith("/start")) {
            String payload = trimmed.length() > 6 ? trimmed.substring(6).trim() : "";
            if (payload.startsWith("link_")) {
                return linkByCode(payload.substring(5), chatId).thenReturn("OK");
            }
            return telegram.sendMessage(chatId,
                    "👋 <b>Ascentra Copy Trading</b>\n\n"
                            + "1. Log in to the app → Profile → Connect Telegram\n"
                            + "2. Tap <b>Generate code</b>\n"
                            + "3. Send here: <code>/link YOUR_CODE</code>\n\n"
                            + "Bot: @" + botUsername)
                    .thenReturn("OK");
        }

        if (trimmed.startsWith("/link")) {
            String code = trimmed.replaceFirst("(?i)/link", "").trim();
            return linkByCode(code, chatId).thenReturn("OK");
        }

        if ("/help".equalsIgnoreCase(trimmed)) {
            return telegram.sendMessage(chatId,
                    "<b>Commands</b>\n"
                            + "<code>/link 123456</code> — link app account (code from Profile)\n"
                            + "<code>/status</code> — account info\n"
                            + "<code>/help</code> — this message")
                    .thenReturn("OK");
        }

        if ("/status".equalsIgnoreCase(trimmed)) {
            return userRepo.findAll()
                    .filter(u -> chatId.equals(u.getTelegramChatId()))
                    .next()
                    .flatMap(u -> telegram.sendMessage(chatId,
                            "Account: <b>" + u.getName() + "</b>\nRole: " + u.getRole() + "\nAlerts: enabled"))
                    .switchIfEmpty(telegram.sendMessage(chatId,
                            "Not linked yet. Open the app → Profile → Telegram → generate code → <code>/link CODE</code>"))
                    .thenReturn("OK");
        }

        return Mono.just("OK");
    }

    private static String normalizeUsername(String username) {
        if (username == null) return "Copy_tradingsBot";
        return username.startsWith("@") ? username.substring(1) : username;
    }

    private static Map<String, Object> defaultPreferences() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tradeAlerts", true);
        p.put("riskAlerts", true);
        p.put("dailySummary", true);
        p.put("systemAlerts", false);
        p.put("alertOnSuccess", true);
        p.put("alertOnFailure", true);
        p.put("alertOnSkipped", true);
        return p;
    }

    private record LinkCode(UUID userId, Instant expiresAt) {}
}
