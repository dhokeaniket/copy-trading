package com.copytrading.risk;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RiskRuleRepository extends ReactiveCrudRepository<RiskRule, UUID> {
    Mono<RiskRule> findByUserId(UUID userId);
}
