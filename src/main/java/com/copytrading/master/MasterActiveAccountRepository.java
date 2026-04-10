package com.copytrading.master;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface MasterActiveAccountRepository extends ReactiveCrudRepository<MasterActiveAccount, UUID> {
}
