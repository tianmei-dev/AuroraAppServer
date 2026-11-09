package com.aurora.appserver.payment.repository;

import com.aurora.appserver.payment.domain.PaymentProviderCallEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentProviderCallRepository extends JpaRepository<PaymentProviderCallEntity, Long> {

    List<PaymentProviderCallEntity> findByOrderNoOrderByIdAsc(String orderNo);
}
