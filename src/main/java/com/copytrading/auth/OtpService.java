package com.copytrading.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTP service with Redis-backed storage (falls back to in-memory if Redis unavailable).
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_SECONDS = 300;
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    // In-memory fallback
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private final ReactiveStringRedisTemplate redis;
    private final boolean redisAvailable;

    public record OtpEntry(String otp, Instant expiresAt, Instant sentAt, int attempts) {}

    public OtpService(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        // Test Redis connectivity
        boolean available = false;
        try {
            redis.opsForValue().set("otp:ping", "pong", Duration.ofSeconds(5)).block(Duration.ofSeconds(3));
            available = true;
            log.info("OTP_REDIS_ENABLED — OTPs stored in Redis");
        } catch (Exception e) {
            log.warn("OTP_REDIS_UNAVAILABLE — falling back to in-memory: {}", e.getMessage());
        }
        this.redisAvailable = available;
    }

    public String generateAndStore(String phone) {
        String otp = generateOtp();
        log.info("OTP_GENERATED phone={}... otp={}", phone.substring(0, Math.min(6, phone.length())), otp);

        if (redisAvailable) {
            // Store in Redis: otp:<phone> = otp value, with TTL
            redis.opsForValue().set("otp:" + phone, otp, Duration.ofSeconds(OTP_EXPIRY_SECONDS)).subscribe();
            redis.opsForValue().set("otp:sent:" + phone, String.valueOf(Instant.now().getEpochSecond()),
                    Duration.ofSeconds(OTP_EXPIRY_SECONDS)).subscribe();
            redis.opsForValue().set("otp:attempts:" + phone, "0",
                    Duration.ofSeconds(OTP_EXPIRY_SECONDS)).subscribe();
        }
        // Always store in-memory too (as fallback)
        otpStore.put(phone, new OtpEntry(otp, Instant.now().plusSeconds(OTP_EXPIRY_SECONDS), Instant.now(), 0));
        return otp;
    }

    public boolean canResend(String phone) {
        OtpEntry entry = otpStore.get(phone);
        if (entry == null) return true;
        return Instant.now().isAfter(entry.sentAt().plusSeconds(RETRY_AFTER_SECONDS));
    }

    public int getRetryAfter(String phone) {
        OtpEntry entry = otpStore.get(phone);
        if (entry == null) return 0;
        long elapsed = Instant.now().getEpochSecond() - entry.sentAt().getEpochSecond();
        return Math.max(0, RETRY_AFTER_SECONDS - (int) elapsed);
    }

    public boolean verify(String phone, String otp) {
        // Try Redis first
        if (redisAvailable) {
            try {
                String stored = redis.opsForValue().get("otp:" + phone).block(Duration.ofSeconds(2));
                if (stored != null && stored.equals(otp)) {
                    redis.delete("otp:" + phone).subscribe();
                    redis.delete("otp:sent:" + phone).subscribe();
                    redis.delete("otp:attempts:" + phone).subscribe();
                    otpStore.remove(phone);
                    return true;
                }
                // Increment attempts in Redis
                redis.opsForValue().increment("otp:attempts:" + phone).subscribe();
            } catch (Exception e) {
                log.warn("Redis OTP verify failed, using in-memory: {}", e.getMessage());
            }
        }

        // Fallback to in-memory
        OtpEntry entry = otpStore.get(phone);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiresAt())) { otpStore.remove(phone); return false; }
        if (entry.attempts() >= MAX_ATTEMPTS) return false;
        otpStore.put(phone, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        if (entry.otp().equals(otp)) { otpStore.remove(phone); return true; }
        return false;
    }

    public boolean isExpired(String phone) {
        OtpEntry entry = otpStore.get(phone);
        if (entry == null) return true;
        return Instant.now().isAfter(entry.expiresAt());
    }

    public boolean tooManyAttempts(String phone) {
        OtpEntry entry = otpStore.get(phone);
        if (entry == null) return false;
        return entry.attempts() >= MAX_ATTEMPTS;
    }

    private String generateOtp() {
        return String.valueOf(random.nextInt(900000) + 100000);
    }

    public int getExpirySeconds() { return OTP_EXPIRY_SECONDS; }
    public int getRetrySeconds() { return RETRY_AFTER_SECONDS; }
}
