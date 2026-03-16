package com.copytrading.risk;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface RiskLimitsRepository extends ReactiveCrudRepository<RiskLimits, Long> {
  Mono<RiskLimits> findByChildId(Long childId);
}
