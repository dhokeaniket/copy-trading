package com.copytrading.security;

import com.copytrading.broker.BrokerAccount;
import org.springframework.stereotype.Component;

@Component
public class BrokerCredentials {

    private final CredentialCrypto crypto;

    public BrokerCredentials(CredentialCrypto crypto) {
        this.crypto = crypto;
    }

    public String accessToken(BrokerAccount account) {
        return account == null ? null : crypto.decrypt(account.getAccessToken());
    }

    public String apiSecret(BrokerAccount account) {
        return account == null ? null : crypto.decrypt(account.getApiSecret());
    }

    public void encryptSensitiveFields(BrokerAccount account) {
        if (account == null) return;
        if (account.getAccessToken() != null) {
            account.setAccessToken(crypto.encrypt(account.getAccessToken()));
        }
        if (account.getApiSecret() != null) {
            account.setApiSecret(crypto.encrypt(account.getApiSecret()));
        }
        if (account.getProxyPass() != null) {
            account.setProxyPass(crypto.encrypt(account.getProxyPass()));
        }
    }

    /**
     * Encrypt only the access token field. Use after login when apiSecret/proxyPass
     * are already encrypted in the DB — avoids double-encrypting them.
     */
    public void encryptAccessTokenOnly(BrokerAccount account) {
        if (account == null) return;
        if (account.getAccessToken() != null) {
            account.setAccessToken(crypto.encrypt(account.getAccessToken()));
        }
    }

    public String proxyPass(BrokerAccount account) {
        return account == null ? null : crypto.decrypt(account.getProxyPass());
    }
}
