package com.copytrading.admin.repository;

import com.copytrading.admin.model.SystemSetting;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSetting, String> {
}
