package com.aurora.appserver.payment.service;

import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.provider.PaymentLink;

/** PRODUCTION clients only ever receive a real cashier URL; test payment instructions never leave test envs. */
final class PaymentLinkPolicy {

    private PaymentLinkPolicy() {
    }

    static boolean allowed(PaymentEnvironment environment, PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (link.kind() == PaymentLink.PayKind.URL) {
            return link.value() != null && !link.value().isBlank();
        }
        return environment != PaymentEnvironment.PRODUCTION;
    }
}
