package com.copytrading.risk;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RiskLimitsRepository extends ReactiveCrudRepository<RiskLimits, UUID> {

    Mono<RiskLimits> findByChildId(UUID childId);
}
