package com.copytrading.auth;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserAccountRepository extends ReactiveCrudRepository<UserAccount, Long> {
  Mono<UserAccount> findByUsername(String username);
}
