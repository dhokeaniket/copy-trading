package com.copytrading.auth;

import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
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
 * OTP via Twilio Verify (preferred) or in-memory dev fallback when Twilio is not configured.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int OTP_EXPIRY_SECONDS = 600;
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final String REDIS_SENT_PREFIX = "otp:sent:";
    private static final String REDIS_ATTEMPTS_PREFIX = "otp:attempts:";
    private static final String REDIS_OTP_PREFIX = "otp:";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private final ReactiveStringRedisTemplate redis;
    private final boolean redisAvailable;
    private final TwilioConfig twilio;
    private final String defaultCountryCode;

    public record OtpEntry(String otp, Instant expiresAt, Instant sentAt, int attempts) {}

    public OtpService(ReactiveStringRedisTemplate redis,
                      TwilioConfig twilio,
                      @Value("${twilio.default-country-code:91}") String defaultCountryCode) {
        this.redis = redis;
        this.twilio = twilio;
        this.defaultCountryCode = defaultCountryCode != null ? defaultCountryCode.replace("+", "").trim() : "91";

        boolean available = false;
        try {
            redis.opsForValue().set("otp:ping", "pong", Duration.ofSeconds(5)).block(Duration.ofSeconds(3));
            available = true;
            log.info("OTP_REDIS_ENABLED — rate limits stored in Redis");
        } catch (Exception e) {
            log.warn("OTP_REDIS_UNAVAILABLE — falling back to in-memory: {}", e.getMessage());
        }
        this.redisAvailable = available;
    }

    /** @return true if OTP was sent (or queued by Twilio Verify). */
    public boolean sendOtp(String phone) {
        String formatted = formatPhone(phone);

        if (twilio.isConfigured()) {
            if (!sendViaTwilioVerify(formatted)) {
                return false;
            }
            markSent(formatted, null);
            return true;
        }

        String otp = generateOtp();
        log.info("OTP_GENERATED (dev/local) phone={}... otp={}", maskPhone(formatted), otp);
        markSent(formatted, otp);
        return true;
    }

    private boolean sendViaTwilioVerify(String formattedPhone) {
        try {
            Verification verification = Verification.creator(
                    twilio.getVerifyServiceSid(),
                    formattedPhone,
                    "sms"
            ).create();
            log.info("TWILIO_OTP_SENT phone={}... status={}", maskPhone(formattedPhone), verification.getStatus());
            return "pending".equalsIgnoreCase(verification.getStatus());
        } catch (Exception e) {
            log.error("TWILIO_OTP_SEND_FAILED phone={}... error={}", maskPhone(formattedPhone), e.getMessage(), e);
            return false;
        }
    }

    public boolean canResend(String phone) {
        String formatted = formatPhone(phone);
        Instant sentAt = getSentAt(formatted);
        if (sentAt == null) return true;
        return Instant.now().isAfter(sentAt.plusSeconds(RETRY_AFTER_SECONDS));
    }

    public int getRetryAfter(String phone) {
        String formatted = formatPhone(phone);
        Instant sentAt = getSentAt(formatted);
        if (sentAt == null) return 0;
        long elapsed = Instant.now().getEpochSecond() - sentAt.getEpochSecond();
        return Math.max(0, RETRY_AFTER_SECONDS - (int) elapsed);
    }

    public boolean verify(String phone, String otp) {
        String formatted = formatPhone(phone);

        if (twilio.isConfigured()) {
            boolean approved = verifyViaTwilio(formatted, otp);
            if (approved) {
                clearOtpState(formatted);
            } else {
                incrementAttempts(formatted);
            }
            return approved;
        }

        if (redisAvailable) {
            try {
                String stored = redis.opsForValue().get(REDIS_OTP_PREFIX + formatted).block(Duration.ofSeconds(2));
                if (stored != null && stored.equals(otp)) {
                    clearOtpState(formatted);
                    return true;
                }
                incrementAttempts(formatted);
            } catch (Exception e) {
                log.warn("Redis OTP verify failed, using in-memory: {}", e.getMessage());
            }
        }

        OtpEntry entry = otpStore.get(formatted);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiresAt())) {
            otpStore.remove(formatted);
            return false;
        }
        if (entry.attempts() >= MAX_ATTEMPTS) return false;
        otpStore.put(formatted, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        if (entry.otp().equals(otp)) {
            clearOtpState(formatted);
            return true;
        }
        return false;
    }

    private boolean verifyViaTwilio(String formattedPhone, String code) {
        try {
            VerificationCheck check = VerificationCheck.creator(twilio.getVerifyServiceSid())
                    .setTo(formattedPhone)
                    .setCode(code)
                    .create();
            boolean approved = "approved".equalsIgnoreCase(check.getStatus());
            log.info("TWILIO_OTP_VERIFY phone={}... status={}", maskPhone(formattedPhone), check.getStatus());
            return approved;
        } catch (Exception e) {
            log.warn("TWILIO_OTP_VERIFY_FAILED phone={}... error={}", maskPhone(formattedPhone), e.getMessage());
            return false;
        }
    }

    public boolean isExpired(String phone) {
        if (twilio.isConfigured()) {
            Instant sentAt = getSentAt(formatPhone(phone));
            if (sentAt == null) return true;
            return Instant.now().isAfter(sentAt.plusSeconds(OTP_EXPIRY_SECONDS));
        }
        OtpEntry entry = otpStore.get(formatPhone(phone));
        if (entry == null) return true;
        return Instant.now().isAfter(entry.expiresAt());
    }

    public boolean tooManyAttempts(String phone) {
        String formatted = formatPhone(phone);
        int attempts = getAttempts(formatted);
        if (attempts >= MAX_ATTEMPTS) return true;
        OtpEntry entry = otpStore.get(formatted);
        return entry != null && entry.attempts() >= MAX_ATTEMPTS;
    }

    public int getExpirySeconds() {
        return OTP_EXPIRY_SECONDS;
    }

    public int getRetrySeconds() {
        return RETRY_AFTER_SECONDS;
    }

    /** E.164 — +91 for India when country code omitted. */
    public String formatPhone(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        String p = phone.trim().replaceAll("[\\s-]", "");
        if (p.startsWith("+")) return p;
        if (p.startsWith("00")) return "+" + p.substring(2);
        if (p.length() > 10 && p.startsWith(defaultCountryCode)) return "+" + p;
        return "+" + defaultCountryCode + p;
    }

    private void markSent(String formattedPhone, String otpOrNull) {
        Instant now = Instant.now();
        if (redisAvailable) {
            Duration ttl = Duration.ofSeconds(OTP_EXPIRY_SECONDS);
            if (otpOrNull != null) {
                redis.opsForValue().set(REDIS_OTP_PREFIX + formattedPhone, otpOrNull, ttl).subscribe();
            }
            redis.opsForValue().set(REDIS_SENT_PREFIX + formattedPhone, String.valueOf(now.getEpochSecond()), ttl).subscribe();
            redis.opsForValue().set(REDIS_ATTEMPTS_PREFIX + formattedPhone, "0", ttl).subscribe();
        }
        if (otpOrNull != null) {
            otpStore.put(formattedPhone, new OtpEntry(otpOrNull, now.plusSeconds(OTP_EXPIRY_SECONDS), now, 0));
        } else {
            otpStore.put(formattedPhone, new OtpEntry("TWILIO", now.plusSeconds(OTP_EXPIRY_SECONDS), now, 0));
        }
    }

    private void clearOtpState(String formattedPhone) {
        otpStore.remove(formattedPhone);
        if (redisAvailable) {
            redis.delete(REDIS_OTP_PREFIX + formattedPhone).subscribe();
            redis.delete(REDIS_SENT_PREFIX + formattedPhone).subscribe();
            redis.delete(REDIS_ATTEMPTS_PREFIX + formattedPhone).subscribe();
        }
    }

    private Instant getSentAt(String formattedPhone) {
        if (redisAvailable) {
            try {
                String sentAt = redis.opsForValue().get(REDIS_SENT_PREFIX + formattedPhone).block(Duration.ofSeconds(2));
                if (sentAt != null) {
                    return Instant.ofEpochSecond(Long.parseLong(sentAt));
                }
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(formattedPhone);
        return entry != null ? entry.sentAt() : null;
    }

    private int getAttempts(String formattedPhone) {
        if (redisAvailable) {
            try {
                String a = redis.opsForValue().get(REDIS_ATTEMPTS_PREFIX + formattedPhone).block(Duration.ofSeconds(2));
                if (a != null) return Integer.parseInt(a);
            } catch (Exception ignored) { /* fall through */ }
        }
        OtpEntry entry = otpStore.get(formattedPhone);
        return entry != null ? entry.attempts() : 0;
    }

    private void incrementAttempts(String formattedPhone) {
        if (redisAvailable) {
            redis.opsForValue().increment(REDIS_ATTEMPTS_PREFIX + formattedPhone).subscribe();
        }
        OtpEntry entry = otpStore.get(formattedPhone);
        if (entry != null) {
            otpStore.put(formattedPhone, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        }
    }

    private String generateOtp() {
        return String.valueOf(random.nextInt(900000) + 100000);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, Math.min(6, phone.length())) + "...";
    }
}
