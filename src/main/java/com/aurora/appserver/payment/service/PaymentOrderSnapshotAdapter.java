package com.aurora.appserver.payment.service;

import com.aurora.appserver.membership.service.PaidOrderSnapshotSource;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Exposes confirmed (PAID / FULFILLED) order snapshots to the membership module. */
@Component
public class PaymentOrderSnapshotAdapter implements PaidOrderSnapshotSource {

    private final PaymentOrderRepository orderRepository;

    public PaymentOrderSnapshotAdapter(PaymentOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public Optional<PaidOrderSnapshot> findConfirmed(Long appId, String orderNo) {
        return orderRepository.findByAppIdAndOrderNo(appId, orderNo)
                .filter(o -> o.getStatus() == PaymentOrderStatus.PAID || o.getStatus() == PaymentOrderStatus.FULFILLED)
                .map(o -> new PaidOrderSnapshot(
                        o.getAppId(),
                        o.getUserId(),
                        o.getOrderNo(),
                        o.getPlanId(),
                        o.getPlanCode(),
                        o.getDurationDays(),
                        o.isLifetime()));
    }
}
