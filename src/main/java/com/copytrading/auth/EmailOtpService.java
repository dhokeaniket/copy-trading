package com.copytrading.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private static final String REDIS_UID_PREFIX = "email:otp:uid:";
    private static final String REDIS_UID_SENT_PREFIX = "email:otp:sent:uid:";
    private static final String REDIS_UID_ATTEMPTS_PREFIX = "email:otp:attempts:uid:";
    private static final String PW_REDIS_PREFIX = "email:pwreset:";
    private static final String PW_REDIS_SENT_PREFIX = "email:pwreset:sent:";
    private static final String PW_REDIS_ATTEMPTS_PREFIX = "email:pwreset:attempts:";
    private static final Duration REDIS_OP_TIMEOUT = Duration.ofSeconds(5);

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
            redisBlocking(() -> {
                redis.opsForValue().set("email:otp:ping", "pong", Duration.ofSeconds(5)).block(Duration.ofSeconds(3));
                return true;
            });
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
        return sendOtp(normalizeEmail(email), email, OtpPurpose.LOGIN);
    }

    /** Login OTP keyed by user id (shared via Redis across JVM restarts/instances). */
    public boolean sendLoginOtp(java.util.UUID userId, String email) {
        String normalized = normalizeEmail(email);
        clearOtpState(normalized, OtpPurpose.LOGIN);
        return sendOtp(loginStorageKey(userId), normalized, OtpPurpose.LOGIN);
    }

    public boolean verifyLogin(java.util.UUID userId, String otp) {
        String code = otp == null ? "" : otp.trim();
        return verify(loginStorageKey(userId), code, OtpPurpose.LOGIN);
    }

    public boolean canResendLogin(java.util.UUID userId) {
        return canResend(loginStorageKey(userId), OtpPurpose.LOGIN);
    }

    public int getLoginRetryAfter(java.util.UUID userId) {
        return getRetryAfter(loginStorageKey(userId), OtpPurpose.LOGIN);
    }

    public boolean isLoginExpired(java.util.UUID userId) {
        return isExpired(loginStorageKey(userId), OtpPurpose.LOGIN);
    }

    public boolean tooManyLoginAttempts(java.util.UUID userId) {
        return tooManyAttempts(loginStorageKey(userId), OtpPurpose.LOGIN);
    }

    private static String loginStorageKey(java.util.UUID userId) {
        return "uid:" + userId;
    }

    public boolean sendPasswordResetOtp(String email) {
        String normalized = normalizeEmail(email);
        return sendOtp(normalized, email, OtpPurpose.PASSWORD_RESET);
    }

    public boolean verify(String email, String otp) {
        String code = otp == null ? "" : otp.trim();
        return verify(normalizeEmail(email), code, OtpPurpose.LOGIN);
    }

    public boolean verifyPasswordReset(String email, String otp) {
        String code = otp == null ? "" : otp.trim();
        return verify(normalizeEmail(email), code, OtpPurpose.PASSWORD_RESET);
    }

    public boolean canResend(String email) {
        return canResend(normalizeEmail(email), OtpPurpose.LOGIN);
    }

    public boolean canResendPasswordReset(String email) {
        return canResend(normalizeEmail(email), OtpPurpose.PASSWORD_RESET);
    }

    public int getRetryAfter(String email) {
        return getRetryAfter(normalizeEmail(email), OtpPurpose.LOGIN);
    }

    public boolean isExpired(String email) {
        return isExpired(normalizeEmail(email), OtpPurpose.LOGIN);
    }

    public boolean tooManyAttempts(String email) {
        return tooManyAttempts(normalizeEmail(email), OtpPurpose.LOGIN);
    }

    public boolean tooManyPasswordResetAttempts(String email) {
        return tooManyAttempts(normalizeEmail(email), OtpPurpose.PASSWORD_RESET);
    }

    public boolean isPasswordResetExpired(String email) {
        return isExpired(normalizeEmail(email), OtpPurpose.PASSWORD_RESET);
    }

    private boolean sendOtp(String storageKey, String emailTo, OtpPurpose purpose) {
        String otp = generateOtp();
        if (!sendEmail(normalizeEmail(emailTo), otp, purpose)) {
            return false;
        }
        markSent(storageKey, otp, purpose);
        return true;
    }

    private boolean verify(String storageKey, String otp, OtpPurpose purpose) {
        String memKey = storeKey(storageKey, purpose);

        // In-memory first (correct for single-node EC2; avoids stale Redis from prior runs)
        OtpEntry entry = otpStore.get(memKey);
        if (entry != null) {
            if (Instant.now().isAfter(entry.expiresAt())) {
                otpStore.remove(memKey);
            } else if (entry.attempts() < MAX_ATTEMPTS && entry.otp().equals(otp)) {
                clearOtpState(storageKey, purpose);
                return true;
            }
        }

        if (redisAvailable) {
            try {
                String stored = redisBlocking(() ->
                        redis.opsForValue().get(otpRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2)));
                if (stored != null && stored.equals(otp)) {
                    clearOtpState(storageKey, purpose);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Redis email OTP verify failed: {}", e.getMessage());
            }
        }

        if (entry != null && Instant.now().isBefore(entry.expiresAt()) && entry.attempts() < MAX_ATTEMPTS) {
            otpStore.put(memKey, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        }
        incrementAttempts(storageKey, purpose);
        log.warn("EMAIL_OTP_VERIFY_FAILED purpose={} key={} hasMem={} redis={} attempts={}",
                purpose.name(), maskStorageKey(storageKey), entry != null, redisAvailable,
                entry != null ? entry.attempts() : getAttempts(storageKey, purpose));
        return false;
    }

    private boolean canResend(String storageKey, OtpPurpose purpose) {
        Instant sentAt = getSentAt(storageKey, purpose);
        if (sentAt == null) return true;
        return Instant.now().isAfter(sentAt.plusSeconds(RETRY_AFTER_SECONDS));
    }

    private int getRetryAfter(String storageKey, OtpPurpose purpose) {
        Instant sentAt = getSentAt(storageKey, purpose);
        if (sentAt == null) return 0;
        long elapsed = Instant.now().getEpochSecond() - sentAt.getEpochSecond();
        return Math.max(0, RETRY_AFTER_SECONDS - (int) elapsed);
    }

    private boolean isExpired(String storageKey, OtpPurpose purpose) {
        String memKey = storeKey(storageKey, purpose);
        OtpEntry entry = otpStore.get(memKey);
        if (entry != null) return Instant.now().isAfter(entry.expiresAt());
        if (redisAvailable) {
            try {
                String stored = redisBlocking(() ->
                        redis.opsForValue().get(otpRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2)));
                if (stored != null) return false;
            } catch (Exception ignored) { /* fall through */ }
        }
        return getSentAt(storageKey, purpose) == null;
    }

    private boolean tooManyAttempts(String storageKey, OtpPurpose purpose) {
        int attempts = getAttempts(storageKey, purpose);
        if (attempts >= MAX_ATTEMPTS) return true;
        OtpEntry entry = otpStore.get(storeKey(storageKey, purpose));
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

    private static String storeKey(String storageKey, OtpPurpose purpose) {
        return purpose.name() + ":" + storageKey;
    }

    private static String otpRedisKey(String storageKey, OtpPurpose purpose) {
        if (purpose == OtpPurpose.LOGIN && storageKey.startsWith("uid:")) {
            return REDIS_UID_PREFIX + storageKey.substring(4);
        }
        return purpose.redisPrefix + storageKey;
    }

    private static String sentRedisKey(String storageKey, OtpPurpose purpose) {
        if (purpose == OtpPurpose.LOGIN && storageKey.startsWith("uid:")) {
            return REDIS_UID_SENT_PREFIX + storageKey.substring(4);
        }
        return purpose.sentPrefix + storageKey;
    }

    private static String attemptsRedisKey(String storageKey, OtpPurpose purpose) {
        if (purpose == OtpPurpose.LOGIN && storageKey.startsWith("uid:")) {
            return REDIS_UID_ATTEMPTS_PREFIX + storageKey.substring(4);
        }
        return purpose.attemptsPrefix + storageKey;
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

    private void markSent(String storageKey, String otp, OtpPurpose purpose) {
        Instant now = Instant.now();
        String memKey = storeKey(storageKey, purpose);
        if (redisAvailable) {
            Duration ttl = Duration.ofSeconds(OTP_EXPIRY_SECONDS);
            try {
                redisBlockingVoid(() -> {
                    redis.opsForValue().set(otpRedisKey(storageKey, purpose), otp, ttl).block(Duration.ofSeconds(3));
                    redis.opsForValue().set(sentRedisKey(storageKey, purpose), String.valueOf(now.getEpochSecond()), ttl)
                            .block(Duration.ofSeconds(3));
                    redis.opsForValue().set(attemptsRedisKey(storageKey, purpose), "0", ttl).block(Duration.ofSeconds(3));
                });
            } catch (Exception e) {
                log.warn("Redis email OTP store failed: {}", e.getMessage());
            }
        }
        otpStore.put(memKey, new OtpEntry(otp, now.plusSeconds(OTP_EXPIRY_SECONDS), now, 0));
        log.info("EMAIL_OTP_STORED purpose={} key={}", purpose.name(), maskStorageKey(storageKey));
    }

    private void clearOtpState(String storageKey, OtpPurpose purpose) {
        otpStore.remove(storeKey(storageKey, purpose));
        if (redisAvailable) {
            try {
                redisBlockingVoid(() -> {
                    redis.delete(otpRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2));
                    redis.delete(sentRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2));
                    redis.delete(attemptsRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2));
                });
            } catch (Exception e) {
                log.warn("Redis email OTP clear failed: {}", e.getMessage());
            }
        }
    }

    private Instant getSentAt(String storageKey, OtpPurpose purpose) {
        if (redisAvailable) {
            try {
                String sentAt = redisBlocking(() ->
                        redis.opsForValue().get(sentRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2)));
                if (sentAt != null) return Instant.ofEpochSecond(Long.parseLong(sentAt));
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(storeKey(storageKey, purpose));
        return entry != null ? entry.sentAt() : null;
    }

    private int getAttempts(String storageKey, OtpPurpose purpose) {
        if (redisAvailable) {
            try {
                String a = redisBlocking(() ->
                        redis.opsForValue().get(attemptsRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2)));
                if (a != null) return Integer.parseInt(a);
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(storeKey(storageKey, purpose));
        return entry != null ? entry.attempts() : 0;
    }

    private void incrementAttempts(String storageKey, OtpPurpose purpose) {
        if (redisAvailable) {
            try {
                redisBlockingVoid(() ->
                        redis.opsForValue().increment(attemptsRedisKey(storageKey, purpose)).block(Duration.ofSeconds(2)));
            } catch (Exception e) {
                log.warn("Redis email OTP increment failed: {}", e.getMessage());
            }
        }
        String memKey = storeKey(storageKey, purpose);
        OtpEntry entry = otpStore.get(memKey);
        if (entry != null) {
            otpStore.put(memKey, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
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

    private static String maskStorageKey(String storageKey) {
        if (storageKey == null) return "****";
        if (storageKey.startsWith("uid:")) return "uid:" + storageKey.substring(4, Math.min(storageKey.length(), 12)) + "***";
        return maskEmail(storageKey);
    }

    /** Reactive WebFlux threads forbid block(); run Redis I/O on boundedElastic. */
    private <T> T redisBlocking(Supplier<T> action) {
        return Mono.fromCallable(action::get)
                .subscribeOn(Schedulers.boundedElastic())
                .block(REDIS_OP_TIMEOUT);
    }

    private void redisBlockingVoid(Runnable action) {
        redisBlocking(() -> {
            action.run();
            return null;
        });
    }
}
