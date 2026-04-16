package com.copytrading.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long accessTokenExpSeconds;
    private final long refreshTokenExpSeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.accessTokenExpirationSeconds}") long accessTokenExpSeconds,
            @Value("${jwt.refreshTokenExpirationSeconds}") long refreshTokenExpSeconds) {
        this.key = buildKey(secret);
        this.issuer = issuer;
        this.accessTokenExpSeconds = accessTokenExpSeconds;
        this.refreshTokenExpSeconds = refreshTokenExpSeconds;
    }

    public String generateAccessToken(UUID userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuer(issuer)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(accessTokenExpSeconds)))
                .claim("email", email)
                .claim("role", role)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Short-lived token (5 min) that only allows 2FA verification. */
    public String generatePending2FAToken(UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuer(issuer)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(300)))
                .claim("role", role)
                .claim("pending2fa", true)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuer(issuer)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(refreshTokenExpSeconds)))
                .claim("type", "refresh")
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public long getRefreshTokenExpSeconds() {
        return refreshTokenExpSeconds;
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public String extractRole(String token) {
        try {
            Claims claims = parse(token);
            return (String) claims.get("role");
        } catch (Exception e) { return null; }
    }

    private static SecretKey buildKey(String secret) {
        byte[] raw;
        try {
            raw = Decoders.BASE64.decode(secret);
        } catch (Exception e) {
            raw = secret.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(sha256(raw));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }
}
