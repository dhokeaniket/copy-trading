package com.copytrading.auth;

import com.copytrading.auth.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "Send SMS OTP", description = "Send OTP to registered phone (Twilio)")
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
                    if (!otpService.sendOtp(req.getPhone())) {
                        return Mono.just(Map.<String, Object>of(
                                "success", false,
                                "error", "SMS_FAILED",
                                "message", "Failed to send OTP. Check Twilio config or verify trial phone list."));
                    }
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("success", true);
                    r.put("data", Map.of("expiresIn", otpService.getExpirySeconds(), "retryAfter", otpService.getRetrySeconds()));
                    r.put("message", "OTP sent successfully");
                    return Mono.just(r);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("success", true);
                    r.put("data", Map.of("expiresIn", otpService.getExpirySeconds(), "retryAfter", otpService.getRetrySeconds()));
                    r.put("message", "If this phone is registered, an OTP has been sent.");
                    return Mono.just(r);
                }));
    }

    @Operation(summary = "Verify SMS OTP", description = "Verify phone OTP and get access tokens")
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

    @Operation(summary = "Resend login OTP", description = "Resend code via user's 2FA channel (email or phone)")
    @PostMapping(value = {"/send-login-otp", "/send-email-otp"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> resendLoginOtp(@RequestBody SendEmailOtpRequest req) {
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required"));
        }
        return authService.resendLoginOtp(req.getEmail());
    }

    @Operation(summary = "Verify login OTP", description = "Complete login after password + OTP (email or SMS)")
    @PostMapping(value = {"/verify-login-otp", "/verify-email-otp"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> verifyLoginOtp(
            @RequestBody VerifyEmailOtpRequest req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (req.getEmail() == null || req.getOtp() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "email and otp are required"));
        }
        return authService.verifyLoginOtp(req.getEmail(), req.getOtp(), authorization);
    }

    @Operation(summary = "Register", description = "Create a new user account (MASTER, CHILD, or ADMIN)")
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @Operation(summary = "Login", description = "Email + password. If 2FA enabled, OTP sent via email or phone.")
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> logout(@RequestBody LogoutRequest req) {
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

    @Operation(summary = "Forgot password", description = "Sends 6-digit OTP to registered email (Gmail SMTP)")
    @PostMapping(value = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required"));
        }
        return authService.forgotPassword(req.getEmail());
    }

    @Operation(summary = "Reset password", description = "Use email + otp + newPassword (preferred) or token + newPassword")
    @PostMapping(value = "/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resetPassword(@RequestBody ResetPasswordRequest req) {
        if (req.getNewPassword() == null || req.getNewPassword().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "newPassword is required"));
        }
        return authService.resetPassword(req);
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

    @Operation(summary = "2FA channel options", description = "EMAIL or PHONE — for enable screen")
    @GetMapping("/2fa/options")
    public Mono<Map<String, Object>> get2FAOptions(@AuthenticationPrincipal String userId) {
        return authService.get2FAOptions(UUID.fromString(userId));
    }

    @Operation(summary = "Enable 2FA", description = "Choose EMAIL or PHONE; sends OTP to that channel (no QR)")
    @PostMapping(value = "/2fa/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> enable2FA(@AuthenticationPrincipal String userId,
                                              @RequestBody Enable2FARequest req) {
        if (req.getChannel() == null || req.getChannel().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel is required: EMAIL or PHONE"));
        }
        return authService.enable2FA(UUID.fromString(userId), req.getChannel());
    }

    @Operation(summary = "Verify 2FA OTP", description = "Confirm code from email or SMS to enable 2FA")
    @PostMapping(value = "/2fa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> verify2FA(@AuthenticationPrincipal String userId,
                                                @RequestBody Verify2FARequest req) {
        return authService.verify2FA(UUID.fromString(userId), req.getOtp());
    }

    @Operation(summary = "Disable 2FA", description = "Requires password + OTP on current channel (email or phone)")
    @DeleteMapping(value = "/2fa/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> disable2FA(@AuthenticationPrincipal String userId,
                                                 @RequestBody Disable2FARequest req) {
        return authService.disable2FA(UUID.fromString(userId), req.getPassword(), req.getOtp());
    }

    @PostMapping(value = "/validate-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> validatePassword(@RequestBody Map<String, String> body) {
        String password = body.getOrDefault("password", "");
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        boolean hasLength = password.length() >= 8;
        boolean hasNumber = password.matches(".*\\d.*");
        boolean hasSpecial = password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");
        boolean hasUpper = password.matches(".*[A-Z].*");
        boolean hasLower = password.matches(".*[a-z].*");

        int score = 0;
        if (hasLength) score++;
        if (hasNumber) score++;
        if (hasSpecial) score++;
        if (hasUpper) score++;
        if (hasLower) score++;
        if (password.length() >= 12) score++;

        String strength = score <= 2 ? "WEAK" : score <= 4 ? "MEDIUM" : "STRONG";

        r.put("valid", hasLength && hasNumber && hasSpecial);
        r.put("strength", strength);
        r.put("score", score);
        r.put("checks", Map.of(
                "minLength", hasLength,
                "hasNumber", hasNumber,
                "hasSpecialChar", hasSpecial,
                "hasUppercase", hasUpper,
                "hasLowercase", hasLower
        ));
        return Mono.just(r);
    }
}
