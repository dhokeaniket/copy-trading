package com.copytrading.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/** Registers Telegram webhook on startup when env is configured (EC2). */
@Component
@Order(200)
public class TelegramWebhookBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookBootstrap.class);

    private final WebClient webClient;
    private final String botToken;
    private final boolean enabled;
    private final String webhookBaseUrl;

    public TelegramWebhookBootstrap(
            @Value("${telegram.bot-token:}") String botToken,
            @Value("${telegram.enabled:false}") boolean enabled,
            @Value("${telegram.webhook-base-url:}") String webhookBaseUrl) {
        this.botToken = botToken;
        this.enabled = enabled;
        this.webhookBaseUrl = webhookBaseUrl == null ? "" : webhookBaseUrl.trim();
        this.webClient = WebClient.builder().baseUrl("https://api.telegram.org").build();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || botToken == null || botToken.isBlank() || webhookBaseUrl.isBlank()) {
            return;
        }
        String base = webhookBaseUrl.replaceAll("/+$", "");
        String webhookUrl = base + "/api/v1/telegram/webhook";
        webClient.post()
                .uri("/bot{token}/setWebhook", botToken)
                .bodyValue(Map.of(
                        "url", webhookUrl,
                        "allowed_updates", new String[]{"message"}))
                .retrieve()
                .bodyToMono(Map.class)
                .doOnSuccess(r -> log.info("TELEGRAM_WEBHOOK_REGISTERED url={} result={}", webhookUrl, r))
                .doOnError(e -> log.warn("TELEGRAM_WEBHOOK_REGISTER_FAILED url={} error={}", webhookUrl, e.getMessage()))
                .subscribe();
    }
}
