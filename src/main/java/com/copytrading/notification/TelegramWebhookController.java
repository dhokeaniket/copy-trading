package com.copytrading.notification;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spec §6.5 — {@code POST /api/v1/telegram/webhook} (public). */
@RestController
@RequestMapping("/api/v1/telegram")
public class TelegramWebhookController {

    private final TelegramLinkService linkService;

    public TelegramWebhookController(TelegramLinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> webhook(@RequestBody Map<String, Object> payload) {
        Object messageObj = payload.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            return ok();
        }
        String text = message.get("text") != null ? message.get("text").toString() : "";
        String chatId = null;
        Object chat = message.get("chat");
        if (chat instanceof Map<?, ?> chatMap && chatMap.get("id") != null) {
            chatId = chatMap.get("id").toString();
        }
        if (chatId == null) return ok();
        return linkService.handleWebhookMessage(text, chatId).then(ok());
    }

    private static Mono<Map<String, Object>> ok() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        return Mono.just(r);
    }
}
