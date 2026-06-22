package com.copytrading.admin.repository;

import com.copytrading.admin.model.AdminAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface AdminAuditLogRepository extends ReactiveCrudRepository<AdminAuditLog, UUID> {
    Flux<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Mono<Long> count();
}
