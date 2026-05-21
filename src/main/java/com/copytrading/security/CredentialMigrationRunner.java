package com.copytrading.security;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
/**
 * One-time style migration: encrypt existing plaintext broker tokens/secrets at startup.
 */
@Component
public class CredentialMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialMigrationRunner.class);
    private static final String ENC_PREFIX = "enc:";

    private final BrokerAccountRepository brokerRepo;
    private final CredentialCrypto crypto;
    private final BrokerCredentials credentials;

    public CredentialMigrationRunner(BrokerAccountRepository brokerRepo,
                                     CredentialCrypto crypto,
                                     BrokerCredentials credentials) {
        this.brokerRepo = brokerRepo;
        this.crypto = crypto;
        this.credentials = credentials;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!crypto.isEnabled()) {
            log.info("CREDENTIAL_MIGRATION_SKIP encryption disabled");
            return;
        }
        brokerRepo.findAll()
                .filter(this::needsEncryption)
                .flatMap(a -> {
                    credentials.encryptSensitiveFields(a);
                    return brokerRepo.save(a);
                })
                .collectList()
                .subscribe(
                        list -> {
                            if (!list.isEmpty()) {
                                log.info("CREDENTIAL_MIGRATION_DONE accounts={}", list.size());
                            }
                        },
                        err -> log.warn("CREDENTIAL_MIGRATION_FAILED: {}", err.getMessage())
                );
    }

    private boolean needsEncryption(BrokerAccount a) {
        return isPlaintext(a.getAccessToken()) || isPlaintext(a.getApiSecret());
    }

    private boolean isPlaintext(String value) {
        return value != null && !value.isBlank() && !value.startsWith(ENC_PREFIX);
    }
}
