package com.copytrading.notification;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    public Mono<Map<String, Object>> listNotifications(String userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(UUID.fromString(userId))
                .map(this::toMap)
                .collectList()
                .map(list -> Map.of("notifications", list));
    }

    public Mono<Map<String, Object>> markAsRead(String userId, String notificationId) {
        return repo.findById(UUID.fromString(notificationId))
                .filter(n -> n.getUserId().equals(UUID.fromString(userId)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found")))
                .flatMap(n -> {
                    n.setRead(true);
                    return repo.save(n);
                })
                .map(n -> Map.<String, Object>of("message", "Marked as read", "id", n.getId().toString()));
    }

    public Mono<Map<String, Object>> markAllAsRead(String userId) {
        return repo.markAllReadByUserId(UUID.fromString(userId))
                .map(count -> Map.<String, Object>of("message", "All notifications marked as read", "updated", count));
    }

    public Mono<Map<String, Object>> deleteNotification(String userId, String notificationId) {
        return repo.findById(UUID.fromString(notificationId))
                .filter(n -> n.getUserId().equals(UUID.fromString(userId)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found")))
                .flatMap(n -> repo.delete(n).thenReturn(Map.<String, Object>of("message", "Notification deleted")));
    }

    /** Called internally to push a notification to a user */
    public Mono<Notification> push(UUID userId, String title, String message, String type) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setMessage(message);
        n.setType(type);
        n.setRead(false);
        n.setCreatedAt(Instant.now());
        return repo.save(n);
    }

    private Map<String, Object> toMap(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId().toString());
        m.put("type", n.getType());
        m.put("title", n.getTitle());
        m.put("message", n.getMessage());
        m.put("read", n.isRead());
        m.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        return m;
    }
}
