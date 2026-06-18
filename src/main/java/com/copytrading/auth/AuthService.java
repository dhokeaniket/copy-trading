package com.copytrading.auth;

import com.copytrading.auth.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;
    private final EmailOtpService emailOtpService;
    private final OtpService otpService;

    public AuthService(UserAccountRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordResetTokenRepository resetTokens,
                       JwtService jwtService,
                       PasswordEncoder encoder,
                       EmailOtpService emailOtpService,
                       OtpService otpService) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.jwtService = jwtService;
        this.encoder = encoder;
        this.emailOtpService = emailOtpService;
        this.otpService = otpService;
    }

    // ── 1.1 Register ──
    public Mono<Map<String, Object>> register(RegisterRequest req) {
        if (req.getName() == null || req.getEmail() == null || req.getPassword() == null || req.getRole() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "name, email, password, role are required"));
        }
        if (req.getPassword().length() < 8 || !req.getPassword().matches(".*\\d.*") || !req.getPassword().matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be min 8 chars with at least one number and one special character"));
        }
        String role = req.getRole().toUpperCase();
        if (!Set.of("ADMIN", "MASTER", "CHILD").contains(role)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role"));
        }
        return users.findByEmail(req.getEmail().toLowerCase().trim())
                .flatMap(existing -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Unable to create account. Please try a different email or login to your existing account.")))
                .switchIfEmpty(Mono.defer(() -> {
                    UserAccount u = new UserAccount();
                    u.setName(req.getName().trim());
                    u.setEmail(req.getEmail().toLowerCase().trim());
                    u.setPasswordHash(encoder.encode(req.getPassword()));
                    u.setRole(role);
                    u.setStatus("ACTIVE");
                    u.setPhone(req.getPhone());
                    u.setTwoFactorEnabled(false);
                    u.setTwoFactorSecret(null);
                    u.setCreatedAt(Instant.now());
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).map(saved -> {
                        log.info("USER_REGISTERED id={} email={} role={}", saved.getId(), saved.getEmail(), saved.getRole());
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("userId", saved.getId());
                        resp.put("message", "Registration successful");
                        return resp;
                    });
                }));
    }

    // ── 1.2 Login: password → OTP (email or phone) when 2FA enabled ──
    public Mono<LoginResponse> login(LoginRequest req) {
        return users.findByEmail(req.getEmail().toLowerCase().trim())
                .flatMap(u -> {
                    if (!u.isActive()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Unable to Login. Your account has been Deactivated."));
                    }
                    if (!encoder.matches(req.getPassword(), u.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
                    }
                    return Mono.just(u);
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .flatMap(u -> {
                    if (!u.isTwoFactorEnabled()) {
                        return issueTokens(u).map(tokens -> {
                            LoginResponse resp = LoginResponse.success(
                                    tokens.get("accessToken"), tokens.get("refreshToken"), UserDto.from(u));
                            log.info("USER_LOGIN id={} email={}", u.getId(), u.getEmail());
                            return resp;
                        });
                    }
                    String channel = resolveChannel(u.getTwoFactorChannel());
                    return sendLoginOtp(u, channel);
                });
    }

    public Mono<Map<String, Object>> resendLoginOtp(String email) {
        return users.findByEmail(emailOtpService.normalizeEmail(email))
                .filter(UserAccount::isActive)
                .filter(UserAccount::isTwoFactorEnabled)
                .flatMap(u -> {
                    String channel = resolveChannel(u.getTwoFactorChannel());
                    if (!canResendLoginOtp(u, channel)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Please wait before requesting another OTP"));
                    }
                    if (!deliverLoginOtp(u, channel)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Failed to send OTP. Check email or Twilio configuration."));
                    }
                    return Mono.just(otpSuccessBody(channel));
                })
                .switchIfEmpty(Mono.just(otpSuccessBody(TwoFactorChannel.EMAIL)));
    }

    public Mono<LoginResponse> verifyLoginOtp(String email, String otp) {
        return verifyLoginOtp(email, otp, null);
    }

    public Mono<LoginResponse> verifyLoginOtp(String email, String otp, String authorization) {
        String code = otp == null ? "" : otp.trim();
        return resolveUserForLoginOtp(email, authorization)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .flatMap(u -> {
                    String channel = resolveChannel(u.getTwoFactorChannel());
                    if (tooManyLoginAttempts(u, channel)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Too many failed attempts. Request a new OTP."));
                    }
                    if (isLoginOtpExpired(u, channel)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "OTP has expired. Please login again."));
                    }
                    if (!verifyLoginOtpCode(u, channel, code)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP code"));
                    }
                    return issueTokens(u).map(tokens -> {
                        LoginResponse resp = LoginResponse.success(
                                tokens.get("accessToken"), tokens.get("refreshToken"), UserDto.from(u));
                        log.info("USER_LOGIN_2FA id={} email={} channel={}", u.getId(), u.getEmail(), channel);
                        return resp;
                    });
                });
    }

    /** @deprecated use {@link #verifyLoginOtp} */
    @Deprecated
    public Mono<LoginResponse> verifyEmailOtpLogin(String email, String otp) {
        return verifyLoginOtp(email, otp);
    }

    // ── 1.2b Login by phone (after SMS OTP verification) ──
    public Mono<Map<String, Object>> loginByPhone(String phone) {
        return users.findByPhone(phone)
                .flatMap(u -> {
                    if (!u.isActive()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Unable to Login. Your account has been Deactivated."));
                    }
                    return Mono.just(u);
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials. Please check your phone number or register.")))
                .flatMap(u -> issueTokens(u).map(tokens -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("success", true);
                    Map<String, Object> data = new java.util.LinkedHashMap<>();
                    data.put("accessToken", tokens.get("accessToken"));
                    data.put("refreshToken", tokens.get("refreshToken"));
                    Map<String, Object> userMap = new java.util.LinkedHashMap<>();
                    userMap.put("id", u.getId().toString());
                    userMap.put("name", u.getName());
                    userMap.put("email", u.getEmail());
                    userMap.put("phone", u.getPhone());
                    String role = u.getRole();
                    userMap.put("role", role.substring(0, 1).toUpperCase() + role.substring(1).toLowerCase());
                    userMap.put("twoFactorEnabled", u.isTwoFactorEnabled());
                    data.put("user", userMap);
                    r.put("data", data);
                    log.info("USER_OTP_LOGIN id={} phone={}", u.getId(), phone);
                    return r;
                }));
    }

    public Mono<UserAccount> findByPhone(String phone) {
        return users.findByPhone(phone);
    }

    public Mono<Map<String, Object>> logout(String refreshToken) {
        String hash = sha256Hex(refreshToken);
        return refreshTokens.revokeByTokenHash(hash)
                .thenReturn(Map.of(
                        "success", true,
                        "message", "Logged out successfully"));
    }

    public Mono<Map<String, String>> refreshToken(String refreshToken) {
        String hash = sha256Hex(refreshToken);
        return refreshTokens.findByTokenHashAndRevokedFalse(hash)
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token")))
                .flatMap(rt -> {
                    rt.setRevoked(true);
                    return refreshTokens.save(rt)
                            .then(users.findById(rt.getUserId()))
                            .flatMap(u -> issueTokens(u).map(tokens -> {
                                Map<String, String> resp = new LinkedHashMap<>();
                                resp.put("accessToken", tokens.get("accessToken"));
                                resp.put("refreshToken", tokens.get("refreshToken"));
                                return resp;
                            }));
                });
    }

    /** Sends 6-digit OTP to email (Gmail SMTP). Same response whether or not email exists. */
    public Mono<Map<String, Object>> forgotPassword(String email) {
        String normalized = emailOtpService.normalizeEmail(email);
        return users.findByEmail(normalized)
                .flatMap(u -> {
                    if (!emailOtpService.canResendPasswordReset(normalized)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Please wait before requesting another OTP"));
                    }
                    if (emailOtpService.tooManyPasswordResetAttempts(normalized)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Too many attempts. Request a new OTP."));
                    }
                    if (!emailOtpService.sendPasswordResetOtp(normalized)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Failed to send OTP. Check email configuration."));
                    }
                    log.info("PASSWORD_RESET_OTP_SENT userId={} email={}", u.getId(), normalized);
                    return Mono.just(passwordResetOtpResponse());
                })
                .switchIfEmpty(Mono.just(passwordResetOtpResponse()));
    }

    public Mono<Map<String, String>> resetPassword(ResetPasswordRequest req) {
        validateNewPassword(req.getNewPassword());
        if (req.getEmail() != null && !req.getEmail().isBlank() && req.getOtp() != null && !req.getOtp().isBlank()) {
            return resetPasswordWithEmailOtp(req.getEmail(), req.getOtp(), req.getNewPassword());
        }
        if (req.getToken() != null && !req.getToken().isBlank()) {
            return resetPasswordWithToken(req.getToken(), req.getNewPassword());
        }
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide email + otp + newPassword, or token + newPassword"));
    }

    private Mono<Map<String, String>> resetPasswordWithEmailOtp(String email, String otp, String newPassword) {
        String normalized = emailOtpService.normalizeEmail(email);
        if (emailOtpService.tooManyPasswordResetAttempts(normalized)) {
            return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Request a new OTP."));
        }
        if (emailOtpService.isPasswordResetExpired(normalized)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OTP has expired. Request a new code from forgot password."));
        }
        if (!emailOtpService.verifyPasswordReset(normalized, otp)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP code"));
        }
        return users.findByEmail(normalized)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid credentials")))
                .flatMap(u -> updateUserPassword(u, newPassword))
                .thenReturn(Map.of("message", "Password reset successful"));
    }

    private Mono<Map<String, String>> resetPasswordWithToken(String token, String newPassword) {
        String hash = sha256Hex(token);
        return resetTokens.findByTokenHashAndUsedFalse(hash)
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token")))
                .flatMap(rt -> {
                    rt.setUsed(true);
                    return resetTokens.save(rt)
                            .then(users.findById(rt.getUserId()))
                            .flatMap(u -> updateUserPassword(u, newPassword))
                            .thenReturn(Map.of("message", "Password reset successful"));
                });
    }

    private Mono<UserAccount> updateUserPassword(UserAccount u, String newPassword) {
        u.setPasswordHash(encoder.encode(newPassword));
        u.setUpdatedAt(Instant.now());
        return users.save(u).then(refreshTokens.revokeAllByUserId(u.getId())).thenReturn(u);
    }

    private static void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || !newPassword.matches(".*\\d.*")
                || !newPassword.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be min 8 chars with at least one number and one special character");
        }
    }

    private static Map<String, Object> passwordResetOtpResponse() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", "If the email exists, a verification code was sent.");
        r.put("expiresIn", 600);
        r.put("retryAfter", 60);
        return r;
    }

    public Mono<UserDto> getProfile(UUID userId) {
        return users.findById(userId)
                .map(UserDto::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    }

    public Mono<UserDto> updateProfile(UUID userId, UpdateProfileRequest req) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (req.getName() != null) u.setName(req.getName().trim());
                    if (req.getPhone() != null) u.setPhone(req.getPhone().trim());
                    if (req.getTelegramChatId() != null) u.setTelegramChatId(req.getTelegramChatId().trim());
                    if (req.getNewPassword() != null && !req.getNewPassword().isBlank()) {
                        if (req.getCurrentPassword() == null || !encoder.matches(req.getCurrentPassword(), u.getPasswordHash())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect"));
                        }
                        if (req.getNewPassword().length() < 8 || !req.getNewPassword().matches(".*\\d.*")) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be min 8 chars with at least one number"));
                        }
                        u.setPasswordHash(encoder.encode(req.getNewPassword()));
                    }
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).map(UserDto::from);
                });
    }

    /** Enable 2FA — user chooses EMAIL or PHONE; OTP sent to that channel (no QR). */
    public Mono<Map<String, Object>> enable2FA(UUID userId, String channelRaw) {
        String channel = requireChannel(channelRaw);
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (TwoFactorChannel.PHONE.equals(channel) && (u.getPhone() == null || u.getPhone().isBlank())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Add a phone number to your profile before enabling SMS verification."));
                    }
                    if (!canResendOtp(u, channel)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Please wait before requesting another OTP"));
                    }
                    if (!sendOtp(u, channel)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Failed to send OTP. Check email or Twilio configuration."));
                    }
                    u.setTwoFactorSecret(TwoFactorChannel.pendingMarker(channel));
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).map(saved -> buildEnable2FAResponse(channel, saved.isTwoFactorEnabled()));
                });
    }

    public Mono<Map<String, Object>> get2FAOptions(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .map(u -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("twoFactorEnabled", u.isTwoFactorEnabled());
                    resp.put("twoFactorChannel", u.getTwoFactorChannel());
                    resp.put("channels", List.of(
                            Map.of("id", TwoFactorChannel.EMAIL, "label", "Email", "available", true),
                            Map.of("id", TwoFactorChannel.PHONE, "label", "Phone (SMS)",
                                    "available", u.getPhone() != null && !u.getPhone().isBlank(),
                                    "hint", u.getPhone() == null || u.getPhone().isBlank()
                                            ? "Add a phone number in profile to use SMS"
                                            : maskPhone(u.getPhone()))
                    ));
                    return resp;
                });
    }

    /** Verify OTP to confirm 2FA enable (EMAIL or PHONE per pending channel). */
    public Mono<Map<String, Object>> verify2FA(UUID userId, String otp) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    String pending = TwoFactorChannel.channelFromPending(u.getTwoFactorSecret());
                    final String channel = pending != null ? pending : resolveChannel(u.getTwoFactorChannel());
                    if (!verifyOtp(u, channel, otp)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP code"));
                    }
                    if (!u.isTwoFactorEnabled()) {
                        u.setTwoFactorEnabled(true);
                        u.setTwoFactorChannel(channel);
                        u.setTwoFactorSecret(null);
                        u.setUpdatedAt(Instant.now());
                        return users.save(u).map(saved -> {
                            Map<String, Object> resp = new LinkedHashMap<>();
                            resp.put("message", "Two-factor authentication enabled");
                            resp.put("twoFactorEnabled", true);
                            resp.put("twoFactorChannel", channel);
                            return resp;
                        });
                    }
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("message", "OTP verified");
                    resp.put("twoFactorEnabled", true);
                    resp.put("twoFactorChannel", channel);
                    return Mono.just(resp);
                });
    }

    public Mono<Map<String, String>> disable2FA(UUID userId, String password, String otp) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (!encoder.matches(password, u.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid password"));
                    }
                    if (!u.isTwoFactorEnabled()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "2FA is not enabled"));
                    }
                    String channel = resolveChannel(u.getTwoFactorChannel());
                    if (!verifyOtp(u, channel, otp)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP code"));
                    }
                    u.setTwoFactorEnabled(false);
                    u.setTwoFactorChannel(null);
                    u.setTwoFactorSecret(null);
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).thenReturn(Map.of("message", "Two-factor authentication disabled"));
                });
    }

    public Mono<UserAccount> createUser(String name, String email, String password, String role, String phone) {
        return users.findByEmail(email.toLowerCase().trim())
                .flatMap(existing -> Mono.<UserAccount>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")))
                .switchIfEmpty(Mono.defer(() -> {
                    UserAccount u = new UserAccount();
                    u.setName(name.trim());
                    u.setEmail(email.toLowerCase().trim());
                    u.setPasswordHash(encoder.encode(password));
                    u.setRole(role);
                    u.setStatus("ACTIVE");
                    u.setPhone(phone);
                    u.setTwoFactorEnabled(false);
                    u.setTwoFactorSecret(null);
                    u.setCreatedAt(Instant.now());
                    u.setUpdatedAt(Instant.now());
                    return users.save(u);
                }));
    }

    private Mono<LoginResponse> sendLoginOtp(UserAccount u, String channel) {
        if (!canResendLoginOtp(u, channel)) {
            int retry = TwoFactorChannel.PHONE.equals(channel)
                    ? otpService.getRetryAfter(u.getPhone())
                    : emailOtpService.getLoginRetryAfter(u.getId());
            return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before requesting another OTP. retryAfter=" + retry));
        }
        if (!deliverLoginOtp(u, channel)) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to send OTP. Check email or Twilio configuration."));
        }
        int expires = TwoFactorChannel.PHONE.equals(channel)
                ? otpService.getExpirySeconds()
                : emailOtpService.getExpirySeconds();
        int retry = TwoFactorChannel.PHONE.equals(channel)
                ? otpService.getRetrySeconds()
                : emailOtpService.getRetrySeconds();
        String pending = jwtService.generatePending2FAToken(u.getId(), u.getRole());
        LoginResponse resp = LoginResponse.otpRequired(UserDto.from(u), channel, expires, retry);
        resp.setAccessToken(pending);
        log.info("USER_LOGIN_2FA_OTP_SENT id={} channel={}", u.getId(), channel);
        return Mono.just(resp);
    }

    private static String requireChannel(String channelRaw) {
        String channel = TwoFactorChannel.normalize(channelRaw);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel is required: EMAIL or PHONE");
        }
        return channel;
    }

    private static String resolveChannel(String stored) {
        String channel = TwoFactorChannel.normalize(stored);
        return channel != null ? channel : TwoFactorChannel.EMAIL;
    }

    private Mono<UserAccount> resolveUserForLoginOtp(String email, String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                var claims = jwtService.parse(authorization.substring(7).trim());
                if (Boolean.TRUE.equals(claims.get("pending2fa", Boolean.class))) {
                    UUID id = UUID.fromString(claims.getSubject());
                    return users.findById(id).filter(UserAccount::isActive);
                }
            } catch (Exception ignored) {
                // fall through to email lookup
            }
        }
        return users.findByEmail(emailOtpService.normalizeEmail(email)).filter(UserAccount::isActive);
    }

    private boolean deliverLoginOtp(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.sendOtp(u.getPhone());
        }
        return emailOtpService.sendLoginOtp(u.getId(), u.getEmail());
    }

    private boolean verifyLoginOtpCode(UserAccount u, String channel, String otp) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.verify(u.getPhone(), otp);
        }
        return emailOtpService.verifyLogin(u.getId(), otp);
    }

    private boolean canResendLoginOtp(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.canResend(u.getPhone());
        }
        return emailOtpService.canResendLogin(u.getId());
    }

    private boolean tooManyLoginAttempts(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.tooManyAttempts(u.getPhone());
        }
        return emailOtpService.tooManyLoginAttempts(u.getId());
    }

    private boolean isLoginOtpExpired(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.isExpired(u.getPhone());
        }
        return emailOtpService.isLoginExpired(u.getId());
    }

    private boolean sendOtp(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.sendOtp(u.getPhone());
        }
        return emailOtpService.sendOtp(u.getEmail());
    }

    private boolean verifyOtp(UserAccount u, String channel, String otp) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.verify(u.getPhone(), otp);
        }
        return emailOtpService.verify(u.getEmail(), otp);
    }

    private boolean canResendOtp(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.canResend(u.getPhone());
        }
        return emailOtpService.canResend(u.getEmail());
    }

    private boolean tooManyAttempts(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.tooManyAttempts(u.getPhone());
        }
        return emailOtpService.tooManyAttempts(u.getEmail());
    }

    private boolean isExpired(UserAccount u, String channel) {
        if (TwoFactorChannel.PHONE.equals(channel)) {
            return otpService.isExpired(u.getPhone());
        }
        return emailOtpService.isExpired(u.getEmail());
    }

    private static Map<String, Object> otpSuccessBody(String channel) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("twoFactorChannel", channel);
        if (TwoFactorChannel.PHONE.equals(channel)) {
            r.put("message", "If this account is registered, a code was sent to your phone.");
        } else {
            r.put("message", "If this account is registered, a code was sent to your email.");
        }
        r.put("data", Map.of("expiresIn", 600, "retryAfter", 60));
        return r;
    }

    private static Map<String, Object> buildEnable2FAResponse(String channel, boolean alreadyEnabled) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("twoFactorChannel", channel);
        resp.put("twoFactorEnabled", alreadyEnabled);
        resp.put("expiresIn", 600);
        resp.put("retryAfter", 60);
        if (TwoFactorChannel.PHONE.equals(channel)) {
            resp.put("message", "A verification code was sent to your phone. Enter it below to enable two-factor authentication.");
        } else {
            resp.put("message", "A verification code was sent to your email. Enter it below to enable two-factor authentication.");
        }
        return resp;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return phone.substring(0, Math.min(4, phone.length())) + "****";
    }

    private Mono<Map<String, String>> issueTokens(UserAccount u) {
        String accessToken = jwtService.generateAccessToken(u.getId(), u.getEmail(), u.getRole());
        String rawRefresh = jwtService.generateRefreshToken(u.getId());
        RefreshToken rt = new RefreshToken();
        rt.setUserId(u.getId());
        rt.setTokenHash(sha256Hex(rawRefresh));
        rt.setExpiresAt(Instant.now().plusSeconds(jwtService.getRefreshTokenExpSeconds()));
        rt.setRevoked(false);
        rt.setCreatedAt(Instant.now());
        return refreshTokens.save(rt).map(saved -> {
            Map<String, String> tokens = new LinkedHashMap<>();
            tokens.put("accessToken", accessToken);
            tokens.put("refreshToken", rawRefresh);
            return tokens;
        });
    }

    static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
