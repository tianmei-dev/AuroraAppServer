package com.aurora.appserver.payment.repository;

import com.aurora.appserver.payment.domain.PaymentOrderRequestEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOrderRequestRepository extends JpaRepository<PaymentOrderRequestEntity, Long> {

    Optional<PaymentOrderRequestEntity> findByAppIdAndUserIdAndClientRequestId(
            Long appId, Long userId, String clientRequestId);

    long countByOrderId(Long orderId);
}
