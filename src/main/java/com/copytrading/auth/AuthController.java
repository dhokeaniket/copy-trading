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

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> logout(@RequestBody LogoutRequest req) {
        if (req.getRefreshToken() == null || req.getRefreshToken().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required"));
        }
        return authService.logout(req.getRefreshToken());
    }

    @PostMapping(value = "/refresh-token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> refreshToken(@RequestBody RefreshTokenRequest req) {
        if (req.getRefreshToken() == null || req.getRefreshToken().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required"));
        }
        return authService.refreshToken(req.getRefreshToken());
    }

    @PostMapping(value = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required"));
        }
        return authService.forgotPassword(req.getEmail());
    }

    @PostMapping(value = "/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resetPassword(@RequestBody ResetPasswordRequest req) {
        return authService.resetPassword(req.getToken(), req.getNewPassword());
    }

    @GetMapping("/me")
    public Mono<UserDto> getProfile(@AuthenticationPrincipal String userId) {
        return authService.getProfile(UUID.fromString(userId));
    }

    @PutMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UserDto> updateProfile(@AuthenticationPrincipal String userId,
                                       @RequestBody UpdateProfileRequest req) {
        return authService.updateProfile(UUID.fromString(userId), req);
    }

    @PostMapping("/2fa/enable")
    public Mono<Map<String, String>> enable2FA(@AuthenticationPrincipal String userId) {
        return authService.enable2FA(UUID.fromString(userId));
    }

    @PostMapping(value = "/2fa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> verify2FA(@AuthenticationPrincipal String userId,
                                                @RequestBody Verify2FARequest req) {
        return authService.verify2FA(UUID.fromString(userId), req.getOtp());
    }

    @DeleteMapping(value = "/2fa/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> disable2FA(@AuthenticationPrincipal String userId,
                                                 @RequestBody Disable2FARequest req) {
        return authService.disable2FA(UUID.fromString(userId), req.getPassword(), req.getOtp());
    }
}
