package com.aurora.appserver.auth.repository;

import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * App-scoped user access. Prefer these methods over inherited findById for business use.
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByAppIdAndId(Long appId, Long id);

    boolean existsByAppIdAndId(Long appId, Long id);

    long countByAppId(Long appId);

    long countByAppIdAndStatus(Long appId, UserStatus status);
}
