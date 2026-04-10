package com.copytrading.notification;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface NotificationRepository extends ReactiveCrudRepository<Notification, UUID> {

    Flux<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("UPDATE notifications SET read = true WHERE user_id = :userId")
    Mono<Integer> markAllReadByUserId(UUID userId);
}
