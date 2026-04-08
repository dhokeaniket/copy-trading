package com.copytrading.auth;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserAccountRepository extends ReactiveCrudRepository<UserAccount, UUID> {

    Mono<UserAccount> findByEmail(String email);

    Flux<UserAccount> findByRole(String role);

    Flux<UserAccount> findByStatus(String status);

    Flux<UserAccount> findByRoleAndStatus(String role, String status);

    @Query("SELECT COUNT(*) FROM users WHERE role = :role")
    Mono<Long> countByRole(String role);

    Mono<Boolean> existsByEmail(String email);

    Mono<UserAccount> findByPhone(String phone);

    Mono<Boolean> existsByPhone(String phone);
}
