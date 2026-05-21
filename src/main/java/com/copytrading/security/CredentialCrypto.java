package com.copytrading.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption for broker tokens/secrets at rest.
 * Prefix {@code enc:} marks ciphertext. Plaintext values pass through for backward compatibility.
 */
@Component
public class CredentialCrypto {

    private static final Logger log = LoggerFactory.getLogger(CredentialCrypto.class);
    private static final String PREFIX = "enc:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private final byte[] keyBytes;
    private final boolean enabled;

    public CredentialCrypto(@Value("${copytrading.credential-encryption-key:}") String encryptionKey,
                            @Value("${jwt.secret:}") String jwtSecret) {
        String material = (encryptionKey != null && !encryptionKey.isBlank())
                ? encryptionKey
                : (jwtSecret != null && !jwtSecret.isBlank() ? jwtSecret : "");
        if (material.isBlank()) {
            this.keyBytes = null;
            this.enabled = false;
            log.warn("CREDENTIAL_ENCRYPTION_DISABLED: set copytrading.credential-encryption-key or jwt.secret");
        } else {
            try {
                this.keyBytes = MessageDigest.getInstance("SHA-256")
                        .digest(material.getBytes(StandardCharsets.UTF_8));
                this.enabled = true;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to init credential encryption", e);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || !enabled) return plaintext;
        if (plaintext.startsWith(PREFIX)) return plaintext;
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            log.error("CREDENTIAL_ENCRYPT_FAILED: {}", e.getMessage());
            return plaintext;
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return value;
        if (!value.startsWith(PREFIX) || !enabled) return value;
        try {
            byte[] packed = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(packed, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[packed.length - IV_LEN];
            System.arraycopy(packed, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("CREDENTIAL_DECRYPT_FAILED: {}", e.getMessage());
            return value;
        }
    }
}
