package com.copytrading.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_SECONDS = 300; // 5 minutes
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();

    // In-memory store (replace with Redis in production)
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    public record OtpEntry(String otp, Instant expiresAt, Instant sentAt, int attempts) {}

    public String generateAndStore(String phone) {
        String otp = generateOtp();
        otpStore.put(phone, new OtpEntry(otp, Instant.now().plusSeconds(OTP_EXPIRY_SECONDS), Instant.now(), 0));
        log.info("OTP_GENERATED phone={}... otp={}", phone.substring(0, Math.min(6, phone.length())), otp);
        // TODO: Send via SMS provider (Twilio/MSG91/2Factor)
        // For now, OTP is logged and stored in memory
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
        OtpEntry entry = otpStore.get(phone);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiresAt())) {
            otpStore.remove(phone);
            return false;
        }
        if (entry.attempts() >= MAX_ATTEMPTS) return false;
        // Increment attempts
        otpStore.put(phone, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        if (entry.otp().equals(otp)) {
            otpStore.remove(phone); // consume OTP
            return true;
        }
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
        int num = random.nextInt(900000) + 100000; // 6 digits
        return String.valueOf(num);
    }

    public int getExpirySeconds() { return OTP_EXPIRY_SECONDS; }
    public int getRetrySeconds() { return RETRY_AFTER_SECONDS; }
}
