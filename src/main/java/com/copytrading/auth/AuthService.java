package com.copytrading.auth;

import com.copytrading.auth.dto.*;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    public AuthService(UserAccountRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordResetTokenRepository resetTokens,
                       JwtService jwtService,
                       PasswordEncoder encoder) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.jwtService = jwtService;
        this.encoder = encoder;
    }

    // ── 1.1 Register ──
    public Mono<Map<String, Object>> register(RegisterRequest req) {
        if (req.getName() == null || req.getEmail() == null || req.getPassword() == null || req.getRole() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "name, email, password, role are required"));
        }
        if (req.getPassword().length() < 8 || !req.getPassword().matches(".*\\d.*")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be min 8 chars with at least one number"));
        }
        String role = req.getRole().toUpperCase();
        if (!Set.of("ADMIN", "MASTER", "CHILD").contains(role)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role"));
        }
        return users.findByEmail(req.getEmail().toLowerCase().trim())
                .flatMap(existing -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")))
                .switchIfEmpty(Mono.defer(() -> {
                    UserAccount u = new UserAccount();
                    u.setName(req.getName().trim());
                    u.setEmail(req.getEmail().toLowerCase().trim());
                    u.setPasswordHash(encoder.encode(req.getPassword()));
                    u.setRole(role);
                    u.setStatus("ACTIVE");
                    u.setPhone(req.getPhone());
                    u.setTwoFactorEnabled(false);
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

    // ── 1.2 Login ──
    public Mono<LoginResponse> login(LoginRequest req) {
        return users.findByEmail(req.getEmail().toLowerCase().trim())
                .filter(UserAccount::isActive)
                .filter(u -> encoder.matches(req.getPassword(), u.getPasswordHash()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .flatMap(u -> {
                    UserDto userDto = UserDto.from(u);
                    if (u.isTwoFactorEnabled()) {
                        // Issue a short-lived pending-2FA token so client can call /auth/2fa/verify
                        String pending = jwtService.generatePending2FAToken(u.getId(), u.getRole());
                        LoginResponse resp = LoginResponse.twoFactorRequired(userDto);
                        resp.setAccessToken(pending); // temporary token, only valid for 2FA verify
                        return Mono.just(resp);
                    }
                    return issueTokens(u).map(tokens -> {
                        LoginResponse resp = LoginResponse.success(
                                tokens.get("accessToken"), tokens.get("refreshToken"), userDto);
                        log.info("USER_LOGIN id={} email={}", u.getId(), u.getEmail());
                        return resp;
                    });
                });
    }

    // ── 1.2b Login by phone (after OTP verification) ──
    public Mono<Map<String, Object>> loginByPhone(String phone) {
        return users.findByPhone(phone)
                .filter(UserAccount::isActive)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Phone not registered")))
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

    // ── Find user by phone ──
    public Mono<UserAccount> findByPhone(String phone) {
        return users.findByPhone(phone);
    }

    // ── 1.3 Logout ──
    public Mono<Map<String, String>> logout(String refreshToken) {
        String hash = sha256Hex(refreshToken);
        return refreshTokens.revokeByTokenHash(hash)
                .thenReturn(Map.of("message", "Logged out successfully"));
    }

    // ── 1.4 Refresh Token ──
    public Mono<Map<String, String>> refreshToken(String refreshToken) {
        String hash = sha256Hex(refreshToken);
        return refreshTokens.findByTokenHashAndRevokedFalse(hash)
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token")))
                .flatMap(rt -> {
                    // Revoke old token (rotation)
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

    // ── 1.5 Forgot Password ──
    public Mono<Map<String, String>> forgotPassword(String email) {
        // Always return success to prevent email enumeration
        return users.findByEmail(email.toLowerCase().trim())
                .flatMap(u -> {
                    String rawToken = UUID.randomUUID().toString();
                    PasswordResetToken prt = new PasswordResetToken();
                    prt.setUserId(u.getId());
                    prt.setTokenHash(sha256Hex(rawToken));
                    prt.setExpiresAt(Instant.now().plusSeconds(1800)); // 30 min
                    prt.setUsed(false);
                    prt.setCreatedAt(Instant.now());
                    return resetTokens.save(prt).doOnSuccess(saved ->
                            log.info("PASSWORD_RESET_TOKEN_CREATED userId={} token={}", u.getId(), rawToken));
                })
                .thenReturn(Map.of("message", "If the email exists, a reset link has been sent"))
                .defaultIfEmpty(Map.of("message", "If the email exists, a reset link has been sent"));
    }

    // ── 1.6 Reset Password ──
    public Mono<Map<String, String>> resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || !newPassword.matches(".*\\d.*")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be min 8 chars with at least one number"));
        }
        String hash = sha256Hex(token);
        return resetTokens.findByTokenHashAndUsedFalse(hash)
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token")))
                .flatMap(rt -> {
                    rt.setUsed(true);
                    return resetTokens.save(rt)
                            .then(users.findById(rt.getUserId()))
                            .flatMap(u -> {
                                u.setPasswordHash(encoder.encode(newPassword));
                                u.setUpdatedAt(Instant.now());
                                return users.save(u);
                            })
                            .then(refreshTokens.revokeAllByUserId(rt.getUserId()))
                            .thenReturn(Map.of("message", "Password reset successful"));
                });
    }

    // ── 1.7 Get Profile ──
    public Mono<UserDto> getProfile(UUID userId) {
        return users.findById(userId)
                .map(UserDto::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    }

    // ── 1.8 Update Profile ──
    public Mono<UserDto> updateProfile(UUID userId, UpdateProfileRequest req) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (req.getName() != null) u.setName(req.getName().trim());
                    if (req.getPhone() != null) u.setPhone(req.getPhone().trim());
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

    // ── 1.9 Enable 2FA ──
    public Mono<Map<String, String>> enable2FA(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    String secret = new DefaultSecretGenerator().generate();
                    u.setTwoFactorSecret(secret);
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).map(saved -> {
                        String qrUri = String.format("otpauth://totp/Ascentra:%s?secret=%s&issuer=Ascentra",
                                saved.getEmail(), secret);
                        Map<String, String> resp = new LinkedHashMap<>();
                        resp.put("qrCodeUri", qrUri);
                        resp.put("secret", secret);
                        return resp;
                    });
                });
    }

    // ── 1.10 Verify 2FA OTP ──
    public Mono<Map<String, Object>> verify2FA(UUID userId, String otp) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (u.getTwoFactorSecret() == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "2FA not set up"));
                    }
                    if (!verifyTotp(u.getTwoFactorSecret(), otp)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP"));
                    }
                    // If 2FA was not yet enabled, activate it now (setup flow)
                    if (!u.isTwoFactorEnabled()) {
                        u.setTwoFactorEnabled(true);
                        u.setUpdatedAt(Instant.now());
                        return users.save(u).flatMap(saved -> issueTokens(saved).map(tokens -> {
                            Map<String, Object> resp = new LinkedHashMap<>();
                            resp.put("accessToken", tokens.get("accessToken"));
                            resp.put("refreshToken", tokens.get("refreshToken"));
                            resp.put("message", "2FA enabled and verified");
                            return resp;
                        }));
                    }
                    // Login verification — issue full tokens
                    return issueTokens(u).map(tokens -> {
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("accessToken", tokens.get("accessToken"));
                        resp.put("refreshToken", tokens.get("refreshToken"));
                        resp.put("message", "OTP verified");
                        return resp;
                    });
                });
    }

    // ── 1.11 Disable 2FA ──
    public Mono<Map<String, String>> disable2FA(UUID userId, String password, String otp) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (!encoder.matches(password, u.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid password"));
                    }
                    if (!u.isTwoFactorEnabled() || u.getTwoFactorSecret() == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "2FA is not enabled"));
                    }
                    if (!verifyTotp(u.getTwoFactorSecret(), otp)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP"));
                    }
                    u.setTwoFactorEnabled(false);
                    u.setTwoFactorSecret(null);
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).thenReturn(Map.of("message", "2FA disabled successfully"));
                });
    }

    // ── Internal: create user (for admin) ──
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
                    u.setCreatedAt(Instant.now());
                    u.setUpdatedAt(Instant.now());
                    return users.save(u);
                }));
    }

    // ── Helpers ──
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

    private boolean verifyTotp(String secret, String code) {
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(), new SystemTimeProvider());
        verifier.setAllowedTimePeriodDiscrepancy(2); // allow ±60 seconds window
        return verifier.isValidCode(secret, code);
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
