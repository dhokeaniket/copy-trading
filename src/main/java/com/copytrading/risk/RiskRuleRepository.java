package com.copytrading.risk;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import java.util.UUID;

public interface RiskRuleRepository extends ReactiveCrudRepository<RiskRule, UUID> {
}
