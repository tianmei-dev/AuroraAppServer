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

    PaymentQueryResult queryOrder(String orderNo);
}
