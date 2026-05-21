package com.copytrading.notification;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/** Spec §6.3 — Telegram link & preferences APIs. */
@RestController
@RequestMapping("/api/v1/notifications/telegram")
public class TelegramLinkController {

    private final TelegramLinkService linkService;

    public TelegramLinkController(TelegramLinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping("/generate-link-token")
    public Mono<Map<String, Object>> generateLinkToken(@AuthenticationPrincipal String userId) {
        return linkService.generateLinkToken(UUID.fromString(userId));
    }

    @PostMapping("/unlink")
    public Mono<Map<String, Object>> unlink(@AuthenticationPrincipal String userId) {
        return linkService.unlink(UUID.fromString(userId));
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@AuthenticationPrincipal String userId) {
        return linkService.getStatus(UUID.fromString(userId));
    }

    @PutMapping(value = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> preferences(@AuthenticationPrincipal String userId,
                                                    @RequestBody Map<String, Object> body) {
        return linkService.updatePreferences(UUID.fromString(userId), body);
    }

    @PostMapping("/test")
    public Mono<Map<String, Object>> test(@AuthenticationPrincipal String userId) {
        return linkService.sendTest(UUID.fromString(userId));
    }
}
