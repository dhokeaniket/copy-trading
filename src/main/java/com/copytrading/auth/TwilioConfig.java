package com.copytrading.auth;

import com.twilio.Twilio;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Twilio Verify — OTP send/verify via Twilio (replaces AWS SNS for SMS OTP).
 * Set env: TWILIO_ACCOUNT_SID, TWILIO_API_KEY, TWILIO_API_SECRET, TWILIO_VERIFY_SERVICE_SID
 */
@Configuration
public class TwilioConfig {

    private static final Logger log = LoggerFactory.getLogger(TwilioConfig.class);

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.api-key:}")
    private String apiKey;

    @Value("${twilio.api-secret:}")
    private String apiSecret;

    @Value("${twilio.verify-service-sid:}")
    private String verifyServiceSid;

    public boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && apiSecret != null && !apiSecret.isBlank()
                && verifyServiceSid != null && !verifyServiceSid.isBlank();
    }

    public String getVerifyServiceSid() {
        return verifyServiceSid;
    }

    @PostConstruct
    public void initTwilio() {
        if (!isConfigured()) {
            log.warn("TWILIO_OTP_DISABLED — set TWILIO_ACCOUNT_SID, TWILIO_API_KEY, TWILIO_API_SECRET, TWILIO_VERIFY_SERVICE_SID");
            return;
        }
        Twilio.init(apiKey, apiSecret, accountSid);
        log.info("TWILIO_OTP_ENABLED verifyServiceSid={}...", verifyServiceSid.substring(0, Math.min(8, verifyServiceSid.length())));
    }
}
