package com.copytrading.user;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/** Spec §4 — {@code GET/PUT /api/v1/users/me/profile}. */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserProfileController {

    private final UserProfileService service;

    public UserProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping("/profile")
    public Mono<Map<String, Object>> getProfile(@AuthenticationPrincipal String userId) {
        return service.getMeProfile(UUID.fromString(userId));
    }

    @PutMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateProfile(@AuthenticationPrincipal String userId,
                                                    @RequestBody Map<String, Object> body) {
        return service.updateMeProfile(UUID.fromString(userId), body);
    }
}
