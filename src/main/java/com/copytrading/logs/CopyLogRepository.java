package com.copytrading.logs;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CopyLogRepository extends ReactiveCrudRepository<CopyLog, Long> {

    Flux<CopyLog> findByMasterId(UUID masterId);

    Flux<CopyLog> findByChildId(UUID childId);
}
