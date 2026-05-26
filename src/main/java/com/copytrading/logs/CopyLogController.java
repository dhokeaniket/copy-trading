package com.copytrading.logs;

import com.copytrading.auth.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Legacy global copy logs — scoped to current user. Prefer:
 * GET /api/v1/master/copy/logs or GET /api/v1/child/copy/logs
 */
@RestController
public class CopyLogController {

    private final CopyLogRepository copyLogs;
    private final UserAccountRepository users;

    public CopyLogController(CopyLogRepository copyLogs, UserAccountRepository users) {
        this.copyLogs = copyLogs;
        this.users = users;
    }

    @GetMapping("/api/v1/copy/logs")
    public Mono<Map<String, Object>> getCopyLogs(@AuthenticationPrincipal String userId) {
        UUID uid = UUID.fromString(userId);
        return users.findById(uid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")))
                .flatMap(u -> {
                    if ("ADMIN".equalsIgnoreCase(u.getRole())) {
                        return copyLogs.findAll().collectList().map(list -> Map.<String, Object>of("logs", list));
                    }
                    if ("MASTER".equalsIgnoreCase(u.getRole())) {
                        return copyLogs.findByMasterId(uid).collectList()
                                .map(list -> Map.<String, Object>of("logs", list));
                    }
                    return copyLogs.findByChildId(uid).collectList()
                            .map(list -> Map.<String, Object>of("logs", list));
                });
    }
}
