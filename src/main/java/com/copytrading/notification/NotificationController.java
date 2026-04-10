package com.copytrading.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<Map<String, Object>> listNotifications(@AuthenticationPrincipal String userId) {
        return service.listNotifications(userId);
    }

    @PatchMapping("/{id}/read")
    public Mono<Map<String, Object>> markAsRead(@AuthenticationPrincipal String userId,
                                                 @PathVariable String id) {
        return service.markAsRead(userId, id);
    }

    @PostMapping("/read-all")
    public Mono<Map<String, Object>> markAllAsRead(@AuthenticationPrincipal String userId) {
        return service.markAllAsRead(userId);
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> deleteNotification(@AuthenticationPrincipal String userId,
                                                         @PathVariable String id) {
        return service.deleteNotification(userId, id);
    }
}
