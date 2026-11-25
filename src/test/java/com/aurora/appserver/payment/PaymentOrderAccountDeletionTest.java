package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.payment.domain.PaymentAnomaly;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentOrderAccountDeletionTest {

    @Test
    void expireForAccountDeletion_closesPending() {
        Instant now = Instant.parse("2026-11-25T12:00:00Z");
        PaymentOrderEntity order = pending(now.plus(Duration.ofMinutes(20)));
        assertThat(order.expireForAccountDeletion(now)).isTrue();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.EXPIRED);
        assertThat(order.isAccountDeletedAnomaly()).isTrue();
    }

    @Test
    void latePaidAfterAccountDeletion_keepsFulfillmentBlocked() {
        Instant now = Instant.parse("2026-11-25T12:00:00Z");
        PaymentOrderEntity order = pending(now.minus(Duration.ofMinutes(1)));
        assertThat(order.markExpired(now)).isTrue();
        order.markAnomaly(PaymentAnomaly.ACCOUNT_DELETED);

        assertThat(order.markPaid(now, "t1", null, "wxpay", 590, null)).isTrue();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.isAccountDeletedAnomaly()).isTrue();
        assertThat(order.claimFulfillment(now, Duration.ofMinutes(2))).isFalse();
    }

    /** Subclass to access the protected no-arg constructor from tests. */
    private static final class TestOrder extends PaymentOrderEntity {
    }

    private static PaymentOrderEntity pending(Instant expiresAt) {
        PaymentOrderEntity order = new TestOrder();
        ReflectionTestUtils.setField(order, "appId", 1L);
        ReflectionTestUtils.setField(order, "userId", 2L);
        ReflectionTestUtils.setField(order, "orderNo", "CVTESTACCOUNTDEL001");
        ReflectionTestUtils.setField(order, "clientRequestId", "client-req-1");
        ReflectionTestUtils.setField(order, "planId", 10L);
        ReflectionTestUtils.setField(order, "planCode", PlanCode.MONTHLY);
        ReflectionTestUtils.setField(order, "planDisplayName", "月卡");
        ReflectionTestUtils.setField(order, "amountFen", 590);
        ReflectionTestUtils.setField(order, "currency", "CNY");
        ReflectionTestUtils.setField(order, "durationDays", 30);
        ReflectionTestUtils.setField(order, "lifetime", false);
        ReflectionTestUtils.setField(order, "status", PaymentOrderStatus.PENDING_PAYMENT);
        ReflectionTestUtils.setField(order, "expiresAt", expiresAt);
        return order;
    }
}
