package com.aurora.appserver.payment.repository;

import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Client-facing lookups are always app + user scoped. Lookups by order_no alone are reserved for
 * provider callbacks and internal jobs, where the app is derived from the stored order.
 */
public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

    Optional<PaymentOrderEntity> findByAppIdAndUserIdAndOrderNo(Long appId, Long userId, String orderNo);

    Optional<PaymentOrderEntity> findByAppIdAndUserIdAndId(Long appId, Long userId, Long id);

    Optional<PaymentOrderEntity> findByAppIdAndOrderNo(Long appId, String orderNo);

    /** Internal (callbacks/jobs) only. */
    Optional<PaymentOrderEntity> findByOrderNo(String orderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrderEntity o WHERE o.id = :id")
    Optional<PaymentOrderEntity> findForUpdate(@Param("id") Long id);

    Optional<PaymentOrderEntity>
            findFirstByAppIdAndUserIdAndPlanCodeAndStatusAndProviderAndEnvironmentAndExpiresAtAfterOrderByCreatedAtDesc(
                    Long appId,
                    Long userId,
                    PlanCode planCode,
                    PaymentOrderStatus status,
                    PaymentProviderCode provider,
                    PaymentEnvironment environment,
                    Instant expiresAfter
            );

    long countByAppIdAndUserId(Long appId, Long userId);

    @Query("""
            SELECT o.id FROM PaymentOrderEntity o
            WHERE o.status = com.aurora.appserver.payment.domain.PaymentOrderStatus.PAID
              AND (o.nextFulfillAt IS NULL OR o.nextFulfillAt <= :now)
              AND o.id > :afterId
            ORDER BY o.id
            """)
    List<Long> findIdsDueForFulfillment(@Param("now") Instant now, @Param("afterId") Long afterId, Pageable pageable);

    @Query("""
            SELECT o.id FROM PaymentOrderEntity o
            WHERE o.status = com.aurora.appserver.payment.domain.PaymentOrderStatus.PENDING_PAYMENT
              AND (o.lastQueryAt IS NULL OR o.lastQueryAt <= :queriedBefore)
              AND o.id > :afterId
            ORDER BY o.id
            """)
    List<Long> findPendingIdsDueForQuery(
            @Param("queriedBefore") Instant queriedBefore, @Param("afterId") Long afterId, Pageable pageable);

    @Query("""
            SELECT o.id FROM PaymentOrderEntity o
            WHERE o.status = com.aurora.appserver.payment.domain.PaymentOrderStatus.EXPIRED
              AND o.expiredAt >= :windowStart
              AND (o.lastQueryAt IS NULL OR o.lastQueryAt <= :queriedBefore)
              AND o.id > :afterId
            ORDER BY o.id
            """)
    List<Long> findExpiredIdsForLatePatrol(
            @Param("windowStart") Instant windowStart,
            @Param("queriedBefore") Instant queriedBefore,
            @Param("afterId") Long afterId,
            Pageable pageable);
}
