package com.copytrading.auth;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PasswordResetTokenRepository extends ReactiveCrudRepository<PasswordResetToken, UUID> {

    Mono<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);
}
