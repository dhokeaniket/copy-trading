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
    private static final String PW_REDIS_PREFIX = "email:pwreset:";
    private static final String PW_REDIS_SENT_PREFIX = "email:pwreset:sent:";
    private static final String PW_REDIS_ATTEMPTS_PREFIX = "email:pwreset:attempts:";

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
        return sendOtp(email, OtpPurpose.LOGIN);
    }

    public boolean sendPasswordResetOtp(String email) {
        return sendOtp(email, OtpPurpose.PASSWORD_RESET);
    }

    public boolean verify(String email, String otp) {
        String code = otp == null ? "" : otp.trim();
        return verify(email, code, OtpPurpose.LOGIN);
    }

    public boolean verifyPasswordReset(String email, String otp) {
        return verify(email, otp, OtpPurpose.PASSWORD_RESET);
    }

    public boolean canResend(String email) {
        return canResend(email, OtpPurpose.LOGIN);
    }

    public boolean canResendPasswordReset(String email) {
        return canResend(email, OtpPurpose.PASSWORD_RESET);
    }

    public int getRetryAfter(String email) {
        return getRetryAfter(email, OtpPurpose.LOGIN);
    }

    public boolean isExpired(String email) {
        return isExpired(email, OtpPurpose.LOGIN);
    }

    public boolean tooManyAttempts(String email) {
        return tooManyAttempts(email, OtpPurpose.LOGIN);
    }

    public boolean tooManyPasswordResetAttempts(String email) {
        return tooManyAttempts(email, OtpPurpose.PASSWORD_RESET);
    }

    public boolean isPasswordResetExpired(String email) {
        return isExpired(email, OtpPurpose.PASSWORD_RESET);
    }

    private boolean sendOtp(String email, OtpPurpose purpose) {
        String normalized = normalizeEmail(email);
        String otp = generateOtp();
        if (!sendEmail(normalized, otp, purpose)) {
            return false;
        }
        markSent(normalized, otp, purpose);
        return true;
    }

    private boolean verify(String email, String otp, OtpPurpose purpose) {
        String normalized = normalizeEmail(email);
        String memKey = storeKey(normalized, purpose);

        // In-memory first (correct for single-node EC2; avoids stale Redis from prior runs)
        OtpEntry entry = otpStore.get(memKey);
        if (entry != null) {
            if (Instant.now().isAfter(entry.expiresAt())) {
                otpStore.remove(memKey);
            } else if (entry.attempts() < MAX_ATTEMPTS && entry.otp().equals(otp)) {
                clearOtpState(normalized, purpose);
                return true;
            }
        }

        if (redisAvailable) {
            try {
                String stored = redis.opsForValue().get(purpose.redisPrefix + normalized).block(Duration.ofSeconds(2));
                if (stored != null && stored.equals(otp)) {
                    clearOtpState(normalized, purpose);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Redis email OTP verify failed: {}", e.getMessage());
            }
        }

        if (entry != null && Instant.now().isBefore(entry.expiresAt()) && entry.attempts() < MAX_ATTEMPTS) {
            otpStore.put(memKey, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        }
        incrementAttempts(normalized, purpose);
        log.warn("EMAIL_OTP_VERIFY_FAILED purpose={} to={} hasMem={} attempts={}",
                purpose.name(), maskEmail(normalized), entry != null,
                entry != null ? entry.attempts() : getAttempts(normalized, purpose));
        return false;
    }

    private boolean canResend(String email, OtpPurpose purpose) {
        Instant sentAt = getSentAt(normalizeEmail(email), purpose);
        if (sentAt == null) return true;
        return Instant.now().isAfter(sentAt.plusSeconds(RETRY_AFTER_SECONDS));
    }

    private int getRetryAfter(String email, OtpPurpose purpose) {
        Instant sentAt = getSentAt(normalizeEmail(email), purpose);
        if (sentAt == null) return 0;
        long elapsed = Instant.now().getEpochSecond() - sentAt.getEpochSecond();
        return Math.max(0, RETRY_AFTER_SECONDS - (int) elapsed);
    }

    private boolean isExpired(String email, OtpPurpose purpose) {
        String storeKey = storeKey(normalizeEmail(email), purpose);
        OtpEntry entry = otpStore.get(storeKey);
        if (entry != null) return Instant.now().isAfter(entry.expiresAt());
        return getSentAt(normalizeEmail(email), purpose) == null;
    }

    private boolean tooManyAttempts(String email, OtpPurpose purpose) {
        String normalized = normalizeEmail(email);
        int attempts = getAttempts(normalized, purpose);
        if (attempts >= MAX_ATTEMPTS) return true;
        OtpEntry entry = otpStore.get(storeKey(normalized, purpose));
        return entry != null && entry.attempts() >= MAX_ATTEMPTS;
    }

    private enum OtpPurpose {
        LOGIN(REDIS_PREFIX, REDIS_SENT_PREFIX, REDIS_ATTEMPTS_PREFIX, "login code"),
        PASSWORD_RESET(PW_REDIS_PREFIX, PW_REDIS_SENT_PREFIX, PW_REDIS_ATTEMPTS_PREFIX, "password reset code");

        final String redisPrefix;
        final String sentPrefix;
        final String attemptsPrefix;
        final String label;

        OtpPurpose(String redisPrefix, String sentPrefix, String attemptsPrefix, String label) {
            this.redisPrefix = redisPrefix;
            this.sentPrefix = sentPrefix;
            this.attemptsPrefix = attemptsPrefix;
            this.label = label;
        }
    }

    private static String storeKey(String email, OtpPurpose purpose) {
        return purpose.name() + ":" + email;
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

    private boolean sendEmail(String to, String otp, OtpPurpose purpose) {
        if (!mailConfigured) {
            log.info("EMAIL_OTP (dev) purpose={} to={} otp={}", purpose.name(), maskEmail(to), otp);
            return true;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(appName + " — your " + purpose.label);
            msg.setText("""
                    Your one-time %s is: %s

                    This code expires in %d minutes.
                    If you did not request this, ignore this email.

                    — %s
                    """.formatted(purpose.label, otp, OTP_EXPIRY_SECONDS / 60, appName));
            mailSender.send(msg);
            log.info("EMAIL_OTP_SENT purpose={} to={}", purpose.name(), maskEmail(to));
            return true;
        } catch (Exception e) {
            log.error("EMAIL_OTP_SEND_FAILED purpose={} to={} error={}", purpose.name(), maskEmail(to), e.getMessage(), e);
            return false;
        }
    }

    private void markSent(String email, String otp, OtpPurpose purpose) {
        Instant now = Instant.now();
        String memKey = storeKey(email, purpose);
        clearOtpState(email, purpose);
        if (redisAvailable) {
            Duration ttl = Duration.ofSeconds(OTP_EXPIRY_SECONDS);
            try {
                redis.opsForValue().set(purpose.redisPrefix + email, otp, ttl).block(Duration.ofSeconds(3));
                redis.opsForValue().set(purpose.sentPrefix + email, String.valueOf(now.getEpochSecond()), ttl).block(Duration.ofSeconds(3));
                redis.opsForValue().set(purpose.attemptsPrefix + email, "0", ttl).block(Duration.ofSeconds(3));
            } catch (Exception e) {
                log.warn("Redis email OTP store failed: {}", e.getMessage());
            }
        }
        otpStore.put(memKey, new OtpEntry(otp, now.plusSeconds(OTP_EXPIRY_SECONDS), now, 0));
        log.info("EMAIL_OTP_STORED purpose={} to={}", purpose.name(), maskEmail(email));
    }

    private void clearOtpState(String email, OtpPurpose purpose) {
        otpStore.remove(storeKey(email, purpose));
        if (redisAvailable) {
            redis.delete(purpose.redisPrefix + email).subscribe();
            redis.delete(purpose.sentPrefix + email).subscribe();
            redis.delete(purpose.attemptsPrefix + email).subscribe();
        }
    }

    private Instant getSentAt(String email, OtpPurpose purpose) {
        if (redisAvailable) {
            try {
                String sentAt = redis.opsForValue().get(purpose.sentPrefix + email).block(Duration.ofSeconds(2));
                if (sentAt != null) return Instant.ofEpochSecond(Long.parseLong(sentAt));
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(storeKey(email, purpose));
        return entry != null ? entry.sentAt() : null;
    }

    private int getAttempts(String email, OtpPurpose purpose) {
        if (redisAvailable) {
            try {
                String a = redis.opsForValue().get(purpose.attemptsPrefix + email).block(Duration.ofSeconds(2));
                if (a != null) return Integer.parseInt(a);
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(storeKey(email, purpose));
        return entry != null ? entry.attempts() : 0;
    }

    private void incrementAttempts(String email, OtpPurpose purpose) {
        if (redisAvailable) {
            redis.opsForValue().increment(purpose.attemptsPrefix + email).subscribe();
        }
        String storeKey = storeKey(email, purpose);
        OtpEntry entry = otpStore.get(storeKey);
        if (entry != null) {
            otpStore.put(storeKey, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
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
