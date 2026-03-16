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

@Service
public class JwtService {
  private final SecretKey key;
  private final String issuer;
  private final long expirationSeconds;

  public JwtService(
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.issuer}") String issuer,
      @Value("${jwt.expirationSeconds}") long expirationSeconds
  ) {
    this.key = buildKey(secret);
    this.issuer = issuer;
    this.expirationSeconds = expirationSeconds;
  }

  private static SecretKey buildKey(String secret) {
    byte[] raw;
    try {
      raw = Decoders.BASE64.decode(secret);
    } catch (Exception e) {
      raw = secret.getBytes(StandardCharsets.UTF_8);
    }

    byte[] keyBytes = sha256(raw);
    return Keys.hmacShaKeyFor(keyBytes);
  }

  private static byte[] sha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("sha256_unavailable", e);
    }
  }

  public String generateToken(String subject, Role role, Map<String, Object> claims) {
    Instant now = Instant.now();
    return Jwts.builder()
        .setClaims(claims)
        .setSubject(subject)
        .setIssuer(issuer)
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(now.plusSeconds(expirationSeconds)))
        .claim("role", role.name())
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parserBuilder()
        .setSigningKey(key)
        .build()
        .parseClaimsJws(token)
        .getBody();
  }
}
