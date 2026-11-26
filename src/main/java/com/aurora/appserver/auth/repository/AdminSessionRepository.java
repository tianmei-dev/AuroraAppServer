package com.aurora.appserver.auth.repository;

import com.aurora.appserver.auth.domain.AdminSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminSessionRepository extends JpaRepository<AdminSessionEntity, Long> {

    Optional<AdminSessionEntity> findByTokenHash(String tokenHash);

    Optional<AdminSessionEntity> findByAppIdAndId(Long appId, Long id);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AdminSessionEntity s
            SET s.revokedAt = :revokedAt
            WHERE s.appId = :appId
              AND s.adminUsername = :username
              AND s.revokedAt IS NULL
            """)
    int revokeAllActiveForAdmin(
            @Param("appId") Long appId,
            @Param("username") String username,
            @Param("revokedAt") java.time.Instant revokedAt
    );
}
