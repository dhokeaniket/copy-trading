package com.copytrading.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRY_SECONDS = 300;
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private final SnsClient snsClient;
    private final boolean smsEnabled;

    public record OtpEntry(String otp, Instant expiresAt, Instant sentAt, int attempts) {}

    public OtpService(
            @Value("${aws.accessKeyId:}") String accessKey,
            @Value("${aws.secretAccessKey:}") String secretKey,
            @Value("${aws.region:ap-south-1}") String region) {
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            this.snsClient = SnsClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            this.smsEnabled = true;
            log.info("AWS SNS SMS enabled for OTP delivery");
        } else {
            this.snsClient = null;
            this.smsEnabled = false;
            log.warn("AWS SNS not configured — OTP will be logged only (not sent via SMS)");
        }
    }

    public String generateAndStore(String phone) {
        String otp = generateOtp();
        otpStore.put(phone, new OtpEntry(otp, Instant.now().plusSeconds(OTP_EXPIRY_SECONDS), Instant.now(), 0));
        log.info("OTP_GENERATED phone={}... otp={}", phone.substring(0, Math.min(6, phone.length())), otp);

        if (smsEnabled) {
            try {
                PublishRequest request = PublishRequest.builder()
                        .phoneNumber(phone)
                        .message("Your Ascentra verification code is: " + otp + ". Valid for 5 minutes.")
                        .messageAttributes(Map.of(
                                "AWS.SNS.SMS.SMSType", MessageAttributeValue.builder()
                                        .stringValue("Transactional")
                                        .dataType("String")
                                        .build(),
                                "AWS.SNS.SMS.SenderID", MessageAttributeValue.builder()
                                        .stringValue("ASCNTR")
                                        .dataType("String")
                                        .build()
                        ))
                        .build();
                snsClient.publish(request);
                log.info("OTP_SMS_SENT phone={}", phone);
            } catch (Exception e) {
                log.error("OTP_SMS_FAILED phone={} error={}", phone, e.getMessage());
            }
        }
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
        otpStore.put(phone, new OtpEntry(entry.otp(), entry.expiresAt(), entry.sentAt(), entry.attempts() + 1));
        if (entry.otp().equals(otp)) {
            otpStore.remove(phone);
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
        return String.valueOf(random.nextInt(900000) + 100000);
    }

    public int getExpirySeconds() { return OTP_EXPIRY_SECONDS; }
    public int getRetrySeconds() { return RETRY_AFTER_SECONDS; }
}
