package com.aurora.appserver.auth.repository;

import com.aurora.appserver.auth.domain.AuthSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * App-scoped session lookups. Token hash is globally unique; resolve then verify app_id.
 */
public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, Long> {

    Optional<AuthSessionEntity> findByAppIdAndId(Long appId, Long id);

    Optional<AuthSessionEntity> findByAppIdAndTokenHash(Long appId, String tokenHash);

    Optional<AuthSessionEntity> findByTokenHash(String tokenHash);

    boolean existsByAppIdAndTokenHash(Long appId, String tokenHash);

    long countByAppIdAndUserId(Long appId, Long userId);
}
