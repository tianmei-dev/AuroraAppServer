package com.aurora.appserver.auth.repository;

import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * App-scoped user access. Prefer these methods over inherited findById for business use.
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByAppIdAndId(Long appId, Long id);

    /** Per-user serialization point (e.g. concurrent order creation). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.appId = :appId AND u.id = :id")
    Optional<UserEntity> findForUpdate(@Param("appId") Long appId, @Param("id") Long id);

    boolean existsByAppIdAndId(Long appId, Long id);

    long countByAppId(Long appId);

    long countByAppIdAndStatus(Long appId, UserStatus status);
}
