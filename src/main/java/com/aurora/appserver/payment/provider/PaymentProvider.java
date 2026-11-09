package com.aurora.appserver.payment.provider;

import com.aurora.appserver.payment.domain.PaymentProviderCode;
import java.util.Map;

/**
 * Payment channel abstraction. Business code depends only on this interface.
 * First version: checkout link, notification verification, order query. No refunds.
 */
public interface PaymentProvider {

    PaymentProviderCode code();

    /** True when every credential/URL needed to build links and verify notifications is present. */
    boolean isConfigured();

    /** Non-secret merchant identifier recorded on orders for audit; may be null. */
    String merchantPid();

    /** Pure computation; must not perform network I/O. */
    PaymentLink buildCheckoutLink(CheckoutRequest request);

    /**
     * Verifies merchant id and signature of an inbound notification.
     *
     * @throws PaymentNotificationRejectedException when the notification must not be trusted
     */
    VerifiedNotification verifyNotification(Map<String, String> params);

    /**
     * Authoritative order status from the provider. Must not throw for transport problems;
     * those are reported as ERROR / TIMEOUT (inconclusive), never as "not paid".
     */
    PaymentQueryResult queryOrder(String orderNo);

    /** Body acknowledging a notification so the provider stops retrying (Yusen: "success"). */
    default String notificationAck() {
        return "success";
    }

    /** Body rejecting an untrusted notification. */
    default String notificationReject() {
        return "fail";
    }
}
