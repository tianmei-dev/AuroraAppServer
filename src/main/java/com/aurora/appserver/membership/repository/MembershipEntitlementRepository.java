package com.aurora.appserver.membership.repository;

import com.aurora.appserver.membership.domain.MembershipEntitlementEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipEntitlementRepository
        extends JpaRepository<MembershipEntitlementEntity, MembershipEntitlementEntity.Pk> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM MembershipEntitlementEntity e WHERE e.appId = :appId AND e.userId = :userId")
    Optional<MembershipEntitlementEntity> findForUpdate(@Param("appId") Long appId, @Param("userId") Long userId);

    Optional<MembershipEntitlementEntity> findByAppIdAndUserId(Long appId, Long userId);
}
