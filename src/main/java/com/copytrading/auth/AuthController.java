package com.copytrading.auth;

import com.copytrading.auth.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // 1.1 POST /auth/register
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    // 1.2 POST /auth/login
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    // 1.3 POST /auth/logout
    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> logout(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required"));
        }
        return authService.logout(refreshToken);
    }

    // 1.4 POST /auth/refresh-token
    @PostMapping(value = "/refresh-token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required"));
        }
        return authService.refreshToken(refreshToken);
    }

    // 1.5 POST /auth/forgot-password
    @PostMapping(value = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required"));
        }
        return authService.forgotPassword(email);
    }

    // 1.6 POST /auth/reset-password
    @PostMapping(value = "/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        return authService.resetPassword(body.get("token"), body.get("newPassword"));
    }

    // 1.7 GET /auth/me
    @GetMapping("/me")
    public Mono<UserDto> getProfile(@AuthenticationPrincipal String userId) {
        return authService.getProfile(UUID.fromString(userId));
    }

    // 1.8 PUT /auth/me
    @PutMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UserDto> updateProfile(@AuthenticationPrincipal String userId,
                                       @RequestBody UpdateProfileRequest req) {
        return authService.updateProfile(UUID.fromString(userId), req);
    }

    // 1.9 POST /auth/2fa/enable
    @PostMapping("/2fa/enable")
    public Mono<Map<String, String>> enable2FA(@AuthenticationPrincipal String userId) {
        return authService.enable2FA(UUID.fromString(userId));
    }

    // 1.10 POST /auth/2fa/verify
    @PostMapping(value = "/2fa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> verify2FA(@AuthenticationPrincipal String userId,
                                                @RequestBody Map<String, String> body) {
        return authService.verify2FA(UUID.fromString(userId), body.get("otp"));
    }

    // 1.11 DELETE /auth/2fa/disable
    @DeleteMapping(value = "/2fa/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> disable2FA(@AuthenticationPrincipal String userId,
                                                 @RequestBody Map<String, String> body) {
        return authService.disable2FA(UUID.fromString(userId), body.get("password"), body.get("otp"));
    }
}
