package com.copytrading.notification;

import com.copytrading.ws.TradeUpdatesHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final TradeUpdatesHub hub;

    public NotificationService(NotificationRepository repo, TradeUpdatesHub hub) {
        this.repo = repo;
        this.hub = hub;
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

    /** Persist notification and broadcast on /ws/notifications for live UI updates. */
    public Mono<Notification> push(UUID userId, String title, String message, String type) {
        return push(userId, title, message, type, Map.of());
    }

    public Mono<Notification> push(UUID userId, String title, String message, String type, Map<String, Object> data) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setMessage(message);
        n.setType(type);
        n.setRead(false);
        n.setCreatedAt(Instant.now());
        return repo.save(n).doOnSuccess(saved -> {
            log.info("NOTIFICATION_PUSH userId={} type={} title={}", userId, type, title);
            publishWebSocket(saved, data);
        });
    }

    private void publishWebSocket(Notification n, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"event\":\"NOTIFICATION\",\"data\":{");
        sb.append("\"id\":\"").append(n.getId()).append("\",");
        sb.append("\"userId\":\"").append(n.getUserId()).append("\",");
        sb.append("\"type\":\"").append(jsonEscape(n.getType())).append("\",");
        sb.append("\"title\":\"").append(jsonEscape(n.getTitle())).append("\",");
        sb.append("\"message\":\"").append(jsonEscape(n.getMessage())).append("\",");
        sb.append("\"read\":false,");
        sb.append("\"createdAt\":\"").append(n.getCreatedAt()).append("\"");
        if (data != null && !data.isEmpty()) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                sb.append(",\"").append(jsonEscape(e.getKey())).append("\":");
                appendJsonValue(sb, e.getValue());
            }
        }
        sb.append("}}");
        hub.publish(sb.toString());
    }

    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number num) {
            sb.append(num);
        } else {
            sb.append("\"").append(jsonEscape(String.valueOf(value))).append("\"");
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
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
