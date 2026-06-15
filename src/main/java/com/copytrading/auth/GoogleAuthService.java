package com.copytrading.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;

/**
 * Google Sign-In backend verification.
 *
 * Flow:
 * 1. Frontend does Google Sign-In (popup/redirect), gets an ID token (JWT from Google)
 * 2. Frontend sends ID token to POST /api/v1/auth/google
 * 3. This service verifies the token with Google's public keys
 * 4. If valid: find or create user by google_id/email, issue our JWT
 *
 * Required env: GOOGLE_CLIENT_ID (from Google Cloud Console)
 */
@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    private final UserAccountRepository users;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokens;
    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthService(UserAccountRepository users,
                             JwtService jwtService,
                             RefreshTokenRepository refreshTokens,
                             @Value("${google.client-id:}") String clientId) {
        this.users = users;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;

        if (clientId != null && !clientId.isBlank()) {
            this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(clientId))
                    .build();
        } else {
            // Allow startup without config (will fail at runtime if called)
            this.verifier = null;
            log.warn("GOOGLE_AUTH: No google.client-id configured. Google login will be disabled.");
        }
    }

    /**
     * Verify Google ID token and login/register the user.
     *
     * @param idTokenString The credential string from Google Sign-In
     * @param role          Default role for new users (CHILD if not specified)
     * @return Login response with accessToken + refreshToken
     */
    public Mono<Map<String, Object>> loginWithGoogle(String idTokenString, String role) {
        if (verifier == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google login not configured. Set GOOGLE_CLIENT_ID environment variable."));
        }
        if (idTokenString == null || idTokenString.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "idToken is required"));
        }

        // Google token verification is a blocking I/O call (HTTP to Google's certs)
        return Mono.fromCallable(() -> verifyToken(idTokenString))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(payload -> findOrCreateUser(payload, role));
    }

    private GoogleIdToken.Payload verifyToken(String idTokenString) {
        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token");
            }
            return idToken.getPayload();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("GOOGLE_VERIFY_FAILED: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google token verification failed: " + e.getMessage());
        }
    }

    private Mono<Map<String, Object>> findOrCreateUser(GoogleIdToken.Payload payload, String role) {
        String googleId = payload.getSubject(); // unique Google user ID
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");
        boolean emailVerified = Boolean.TRUE.equals(payload.getEmailVerified());

        if (!emailVerified) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google email not verified"));
        }

        log.info("GOOGLE_LOGIN googleId={} email={} name={}", googleId, email, name);

        // Try to find by google_id first (fastest, handles email changes)
        return users.findByGoogleId(googleId)
                .flatMap(user -> issueTokens(user, false))
                .switchIfEmpty(Mono.defer(() ->
                        // Try by email (user might have registered with email+password before)
                        users.findByEmail(email.toLowerCase().trim())
                                .flatMap(user -> {
                                    // Link Google to existing account
                                    user.setGoogleId(googleId);
                                    if (picture != null) user.setAvatarUrl(picture);
                                    user.setUpdatedAt(Instant.now());
                                    return users.save(user).flatMap(saved -> issueTokens(saved, false));
                                })
                                .switchIfEmpty(Mono.defer(() -> {
                                    // Create new user
                                    UserAccount u = new UserAccount();
                                    u.setName(name != null ? name : email.split("@")[0]);
                                    u.setEmail(email.toLowerCase().trim());
                                    u.setGoogleId(googleId);
                                    u.setAvatarUrl(picture);
                                    u.setPasswordHash(""); // no password for Google-only users
                                    u.setRole(role != null && !role.isBlank() ? role.toUpperCase() : "CHILD");
                                    u.setStatus("ACTIVE");
                                    u.setTwoFactorEnabled(false);
                                    u.setCreatedAt(Instant.now());
                                    u.setUpdatedAt(Instant.now());
                                    return users.save(u).flatMap(saved -> {
                                        log.info("GOOGLE_USER_CREATED id={} email={} role={}", saved.getId(), saved.getEmail(), saved.getRole());
                                        return issueTokens(saved, true);
                                    });
                                }))
                ));
    }

    private Mono<Map<String, Object>> issueTokens(UserAccount user, boolean isNewUser) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String rawRefresh = jwtService.generateRefreshToken(user.getId());

        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256Hex(rawRefresh));
        rt.setExpiresAt(Instant.now().plusSeconds(jwtService.getRefreshTokenExpSeconds()));
        rt.setRevoked(false);
        rt.setCreatedAt(Instant.now());

        return refreshTokens.save(rt).map(saved -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("accessToken", accessToken);
            resp.put("refreshToken", rawRefresh);
            resp.put("user", Map.of(
                    "id", user.getId().toString(),
                    "name", user.getName() != null ? user.getName() : "",
                    "email", user.getEmail(),
                    "role", user.getRole(),
                    "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                    "isNewUser", isNewUser
            ));
            resp.put("provider", "GOOGLE");
            return resp;
        });
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
