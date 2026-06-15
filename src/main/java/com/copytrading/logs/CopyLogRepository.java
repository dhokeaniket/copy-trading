package com.copytrading.logs;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CopyLogRepository extends ReactiveCrudRepository<CopyLog, Long> {

    Flux<CopyLog> findByMasterId(UUID masterId);

    Flux<CopyLog> findByChildId(UUID childId);

    Flux<CopyLog> findByMasterIdAndChildId(UUID masterId, UUID childId);

    Flux<CopyLog> findByCopyGroupId(String copyGroupId);

    Flux<CopyLog> findByMasterTradeId(String masterTradeId);

    Flux<CopyLog> findByMasterIdAndChildStatus(UUID masterId, String childStatus);

    @Modifying
    @Query("DELETE FROM copy_logs WHERE master_id = :masterId OR child_id = :childId")
    Mono<Void> deleteByMasterIdOrChildId(UUID masterId, UUID childId);
}
