package com.aurora.appserver.payment.repository;

import com.aurora.appserver.payment.domain.PaymentNotificationEntity;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentNotificationRepository extends JpaRepository<PaymentNotificationEntity, Long> {

    Optional<PaymentNotificationEntity> findByProviderAndDedupeKey(PaymentProviderCode provider, String dedupeKey);

    List<PaymentNotificationEntity> findByOrderNoOrderByIdAsc(String orderNo);

    /** Verified-but-unconfirmed notifications due for another confirmation attempt. */
    @Query("""
            SELECT DISTINCT n.orderNo FROM PaymentNotificationEntity n
            WHERE n.processResult = com.aurora.appserver.payment.domain.PaymentNotificationEntity.Result.PENDING_CONFIRM
              AND n.receivedAt >= :windowStart
              AND (n.processedAt IS NULL OR n.processedAt <= :retryBefore)
            """)
    List<String> findOrderNosPendingConfirm(
            @Param("windowStart") Instant windowStart,
            @Param("retryBefore") Instant retryBefore,
            Pageable pageable);

    @Modifying
    @Query("""
            UPDATE PaymentNotificationEntity n SET n.processedAt = :now
            WHERE n.orderNo = :orderNo
              AND n.processResult = com.aurora.appserver.payment.domain.PaymentNotificationEntity.Result.PENDING_CONFIRM
            """)
    int touchPendingConfirm(@Param("orderNo") String orderNo, @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE PaymentNotificationEntity n
            SET n.processResult = com.aurora.appserver.payment.domain.PaymentNotificationEntity.Result.APPLIED,
                n.processedAt = :now
            WHERE n.orderNo = :orderNo
              AND n.processResult = com.aurora.appserver.payment.domain.PaymentNotificationEntity.Result.PENDING_CONFIRM
            """)
    int markApplied(@Param("orderNo") String orderNo, @Param("now") Instant now);
}
