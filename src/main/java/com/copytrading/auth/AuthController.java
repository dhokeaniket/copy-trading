package com.copytrading.auth;

import com.copytrading.auth.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Authentication", description = "Register, login, OTP, 2FA, profile management")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;

    public AuthController(AuthService authService, OtpService otpService) {
        this.authService = authService;
        this.otpService = otpService;
    }

    @Operation(summary = "Send OTP", description = "Send OTP to registered phone number for phone-based login")
    @PostMapping(value = "/send-otp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> sendOtp(@RequestBody SendOtpRequest req) {
        if (req.getPhone() == null || req.getPhone().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone is required"));
        }
        if (!otpService.canResend(req.getPhone())) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("success", false);
            r.put("error", "RATE_LIMITED");
            r.put("message", "Please wait before requesting another OTP");
            r.put("data", Map.of("retryAfter", otpService.getRetryAfter(req.getPhone())));
            return Mono.just(r);
        }
        return authService.findByPhone(req.getPhone())
                .flatMap(user -> {
                    otpService.generateAndStore(req.getPhone());
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("success", true);
                    r.put("data", Map.of("expiresIn", otpService.getExpirySeconds(), "retryAfter", otpService.getRetrySeconds()));
                    r.put("message", "OTP sent successfully");
                    return Mono.just(r);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("success", false);
                    r.put("error", "PHONE_NOT_REGISTERED");
                    r.put("message", "No account found with this phone number");
                    return Mono.just(r);
                }));
    }

    @Operation(summary = "Verify OTP", description = "Verify OTP and get access tokens")
    @PostMapping(value = "/verify-otp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> verifyOtp(@RequestBody VerifyOtpRequest req) {
        if (req.getPhone() == null || req.getOtp() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone and otp are required"));
        }
        if (otpService.tooManyAttempts(req.getPhone())) {
            return Mono.just(Map.<String, Object>of("success", false, "error", "TOO_MANY_ATTEMPTS", "message", "Too many failed attempts. Please request a new OTP."));
        }
        if (otpService.isExpired(req.getPhone())) {
            return Mono.just(Map.<String, Object>of("success", false, "error", "OTP_EXPIRED", "message", "OTP has expired. Please request a new one."));
        }
        if (!otpService.verify(req.getPhone(), req.getOtp())) {
            return Mono.just(Map.<String, Object>of("success", false, "error", "INVALID_OTP", "message", "Invalid OTP code"));
        }
        return authService.loginByPhone(req.getPhone());
    }

    @Operation(summary = "Register", description = "Create a new user account (MASTER, CHILD, or ADMIN)")
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @Operation(summary = "Login", description = "Login with email and password. Returns JWT tokens.")
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @Operation(summary = "Logout", description = "Revoke refresh token")
    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> logout(@RequestBody LogoutRequest req) {
        if (req.getRefreshToken() == null || req.getRefreshToken().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required"));
        }
        return authService.logout(req.getRefreshToken());
    }

    @Operation(summary = "Refresh token", description = "Exchange refresh token for new access + refresh tokens")
    @PostMapping(value = "/refresh-token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> refreshToken(@RequestBody RefreshTokenRequest req) {
        if (req.getRefreshToken() == null || req.getRefreshToken().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken is required"));
        }
        return authService.refreshToken(req.getRefreshToken());
    }

    @Operation(summary = "Forgot password", description = "Request password reset link via email")
    @PostMapping(value = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required"));
        }
        return authService.forgotPassword(req.getEmail());
    }

    @Operation(summary = "Reset password", description = "Reset password using token from email")
    @PostMapping(value = "/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resetPassword(@RequestBody ResetPasswordRequest req) {
        return authService.resetPassword(req.getToken(), req.getNewPassword());
    }

    @Operation(summary = "Get my profile", description = "Get current user's profile")
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
