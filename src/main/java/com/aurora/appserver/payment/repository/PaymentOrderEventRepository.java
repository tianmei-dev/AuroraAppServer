package com.aurora.appserver.payment.repository;

import com.aurora.appserver.payment.domain.PaymentOrderEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOrderEventRepository extends JpaRepository<PaymentOrderEventEntity, Long> {

    List<PaymentOrderEventEntity> findByOrderIdOrderByIdAsc(Long orderId);
}
