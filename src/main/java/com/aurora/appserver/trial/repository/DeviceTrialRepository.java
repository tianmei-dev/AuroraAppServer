package com.aurora.appserver.trial.repository;

import com.aurora.appserver.trial.domain.DeviceTrialEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * App-scoped device trial lookups. Same device_key_hash may exist under different app_id.
 */
public interface DeviceTrialRepository extends JpaRepository<DeviceTrialEntity, Long> {

    Optional<DeviceTrialEntity> findByAppIdAndId(Long appId, Long id);

    Optional<DeviceTrialEntity> findByAppIdAndDeviceKeyHash(Long appId, String deviceKeyHash);

    boolean existsByAppIdAndDeviceKeyHash(Long appId, String deviceKeyHash);

    long countByAppId(Long appId);
}
