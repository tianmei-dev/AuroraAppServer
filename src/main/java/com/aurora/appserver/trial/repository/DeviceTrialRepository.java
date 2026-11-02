package com.aurora.appserver.trial.repository;

import com.aurora.appserver.trial.domain.DeviceTrialEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * App-scoped device trial lookups. Same device_key_hash may exist under different app_id.
 */
public interface DeviceTrialRepository extends JpaRepository<DeviceTrialEntity, Long> {

    Optional<DeviceTrialEntity> findByAppIdAndId(Long appId, Long id);

    Optional<DeviceTrialEntity> findByAppIdAndDeviceKeyHash(Long appId, String deviceKeyHash);

    boolean existsByAppIdAndDeviceKeyHash(Long appId, String deviceKeyHash);

    long countByAppId(Long appId);

    /**
     * Atomically ratchet {@code trial_expires_at} earlier. Never extends.
     * Returns number of rows matched (1 if found, even when value unchanged).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE device_trials
            SET trial_expires_at = LEAST(trial_expires_at, :candidateExpiresAt)
            WHERE app_id = :appId AND device_key_hash = :deviceKeyHash
            """, nativeQuery = true)
    int tightenExpiresAtIfEarlier(
            @Param("appId") Long appId,
            @Param("deviceKeyHash") String deviceKeyHash,
            @Param("candidateExpiresAt") Instant candidateExpiresAt
    );
}
