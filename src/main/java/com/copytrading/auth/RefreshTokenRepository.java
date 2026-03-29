package com.copytrading.auth;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, UUID> {

    Mono<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true WHERE user_id = :userId AND revoked = false")
    Mono<Integer> revokeAllByUserId(UUID userId);

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true WHERE token_hash = :tokenHash")
    Mono<Integer> revokeByTokenHash(String tokenHash);
}
