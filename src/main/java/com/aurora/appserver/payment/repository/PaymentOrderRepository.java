package com.aurora.appserver.payment.repository;

import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** App + user scoped order access. Never look up client-facing orders by order_no alone. */
public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

    Optional<PaymentOrderEntity> findByAppIdAndUserIdAndOrderNo(Long appId, Long userId, String orderNo);

    Optional<PaymentOrderEntity> findByAppIdAndUserIdAndId(Long appId, Long userId, Long id);

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
}
