package com.copytrading.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email OTP for login and email-based 2FA (replaces TOTP/QR).
 */
@Service
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);
    private static final int OTP_EXPIRY_SECONDS = 600;
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final String REDIS_PREFIX = "email:otp:";
    private static final String REDIS_SENT_PREFIX = "email:otp:sent:";
    private static final String REDIS_ATTEMPTS_PREFIX = "email:otp:attempts:";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private final ReactiveStringRedisTemplate redis;
    private final boolean redisAvailable;
    private final JavaMailSender mailSender;
    private final boolean mailConfigured;
    private final String fromAddress;
    private final String appName;

    public record OtpEntry(String otp, Instant expiresAt, Instant sentAt, int attempts) {}

    public EmailOtpService(ReactiveStringRedisTemplate redis,
                           JavaMailSender mailSender,
                           @Value("${spring.mail.username:}") String fromAddress,
                           @Value("${app.mail.from:${spring.mail.username:}}") String fromOverride,
                           @Value("${app.name:Ascentra Capital}") String appName) {
        this.redis = redis;
        this.mailSender = mailSender;
        String from = fromOverride != null && !fromOverride.isBlank() ? fromOverride : fromAddress;
        this.fromAddress = from;
        this.appName = appName;
        this.mailConfigured = from != null && !from.isBlank()
                && mailSender != null;

        boolean available = false;
        try {
            redis.opsForValue().set("email:otp:ping", "pong", Duration.ofSeconds(5)).block(Duration.ofSeconds(3));
            available = true;
        } catch (Exception e) {
            log.warn("EMAIL_OTP_REDIS_UNAVAILABLE — using in-memory: {}", e.getMessage());
        }
        this.redisAvailable = available;

        if (mailConfigured) {
            log.info("EMAIL_OTP_ENABLED from={}", maskEmail(from));
        } else {
            log.warn("EMAIL_OTP_DEV_MODE — set MAIL_USERNAME + MAIL_PASSWORD (Gmail app password); OTP logged only");
        }
    }

    public boolean sendOtp(String email) {
        String normalized = normalizeEmail(email);
        String otp = generateOtp();
        if (!sendEmail(normalized, otp)) {
            return false;
        }
        markSent(normalized, otp);
        return true;
    }

    public boolean verify(String email, String otp) {
        String normalized = normalizeEmail(email);
        if (redisAvailable) {
            try {
                String stored = redis.opsForValue().get(REDIS_PREFIX + normalized).block(Duration.ofSeconds(2));
                if (stored != null && stored.equals(otp)) {
                    clearOtpState(normalized);
                    return true;
                }
                incrementAttempts(normalized);
            } catch (Exception e) {
                log.warn("Redis email OTP verify failed: {}", e.getMessage());
            }
        }
        OtpEntry entry = otpStore.get(normalized);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiresAt())) {
            otpStore.remove(normalized);
            return false;
        }
        if (entry.attempts() >= MAX_ATTEMPTS) return false;
        otpStore.put(normalized, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        if (entry.otp().equals(otp)) {
            clearOtpState(normalized);
            return true;
        }
        return false;
    }

    public boolean canResend(String email) {
        Instant sentAt = getSentAt(normalizeEmail(email));
        if (sentAt == null) return true;
        return Instant.now().isAfter(sentAt.plusSeconds(RETRY_AFTER_SECONDS));
    }

    public int getRetryAfter(String email) {
        Instant sentAt = getSentAt(normalizeEmail(email));
        if (sentAt == null) return 0;
        long elapsed = Instant.now().getEpochSecond() - sentAt.getEpochSecond();
        return Math.max(0, RETRY_AFTER_SECONDS - (int) elapsed);
    }

    public boolean isExpired(String email) {
        OtpEntry entry = otpStore.get(normalizeEmail(email));
        if (entry != null) return Instant.now().isAfter(entry.expiresAt());
        return getSentAt(normalizeEmail(email)) == null;
    }

    public boolean tooManyAttempts(String email) {
        String normalized = normalizeEmail(email);
        int attempts = getAttempts(normalized);
        if (attempts >= MAX_ATTEMPTS) return true;
        OtpEntry entry = otpStore.get(normalized);
        return entry != null && entry.attempts() >= MAX_ATTEMPTS;
    }

    public int getExpirySeconds() {
        return OTP_EXPIRY_SECONDS;
    }

    public int getRetrySeconds() {
        return RETRY_AFTER_SECONDS;
    }

    public boolean isMailConfigured() {
        return mailConfigured;
    }

    public String normalizeEmail(String email) {
        if (email == null) return "";
        return email.trim().toLowerCase();
    }

    private boolean sendEmail(String to, String otp) {
        if (!mailConfigured) {
            log.info("EMAIL_OTP (dev) to={} otp={}", maskEmail(to), otp);
            return true;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(appName + " — your login code");
            msg.setText("""
                    Your one-time login code is: %s

                    This code expires in %d minutes.
                    If you did not request this, ignore this email.

                    — %s
                    """.formatted(otp, OTP_EXPIRY_SECONDS / 60, appName));
            mailSender.send(msg);
            log.info("EMAIL_OTP_SENT to={}", maskEmail(to));
            return true;
        } catch (Exception e) {
            log.error("EMAIL_OTP_SEND_FAILED to={} error={}", maskEmail(to), e.getMessage(), e);
            return false;
        }
    }

    private void markSent(String email, String otp) {
        Instant now = Instant.now();
        if (redisAvailable) {
            Duration ttl = Duration.ofSeconds(OTP_EXPIRY_SECONDS);
            redis.opsForValue().set(REDIS_PREFIX + email, otp, ttl).subscribe();
            redis.opsForValue().set(REDIS_SENT_PREFIX + email, String.valueOf(now.getEpochSecond()), ttl).subscribe();
            redis.opsForValue().set(REDIS_ATTEMPTS_PREFIX + email, "0", ttl).subscribe();
        }
        otpStore.put(email, new OtpEntry(otp, now.plusSeconds(OTP_EXPIRY_SECONDS), now, 0));
    }

    private void clearOtpState(String email) {
        otpStore.remove(email);
        if (redisAvailable) {
            redis.delete(REDIS_PREFIX + email).subscribe();
            redis.delete(REDIS_SENT_PREFIX + email).subscribe();
            redis.delete(REDIS_ATTEMPTS_PREFIX + email).subscribe();
        }
    }

    private Instant getSentAt(String email) {
        if (redisAvailable) {
            try {
                String sentAt = redis.opsForValue().get(REDIS_SENT_PREFIX + email).block(Duration.ofSeconds(2));
                if (sentAt != null) return Instant.ofEpochSecond(Long.parseLong(sentAt));
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(email);
        return entry != null ? entry.sentAt() : null;
    }

    private int getAttempts(String email) {
        if (redisAvailable) {
            try {
                String a = redis.opsForValue().get(REDIS_ATTEMPTS_PREFIX + email).block(Duration.ofSeconds(2));
                if (a != null) return Integer.parseInt(a);
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(email);
        return entry != null ? entry.attempts() : 0;
    }

    private void incrementAttempts(String email) {
        if (redisAvailable) {
            redis.opsForValue().increment(REDIS_ATTEMPTS_PREFIX + email).subscribe();
        }
        OtpEntry entry = otpStore.get(email);
        if (entry != null) {
            otpStore.put(email, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        }
    }

    private String generateOtp() {
        return String.valueOf(random.nextInt(900000) + 100000);
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "****";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(3, at)) + "***" + email.substring(at);
    }
}
