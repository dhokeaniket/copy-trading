package com.copytrading.logs;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CopyLogRepository extends ReactiveCrudRepository<CopyLog, Long> {

    Flux<CopyLog> findByMasterId(UUID masterId);

    Flux<CopyLog> findByChildId(UUID childId);

    Flux<CopyLog> findByMasterIdAndChildId(UUID masterId, UUID childId);

    Flux<CopyLog> findByCopyGroupId(String copyGroupId);

    Flux<CopyLog> findByMasterTradeId(String masterTradeId);

    Flux<CopyLog> findByMasterIdAndChildStatus(UUID masterId, String childStatus);
}
