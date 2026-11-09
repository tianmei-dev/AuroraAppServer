package com.aurora.appserver.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.provider.PaymentLink;
import org.junit.jupiter.api.Test;

class PaymentLinkPolicyTest {

    @Test
    void productionOnlyAllowsRealCashierUrls() {
        PaymentLink url = new PaymentLink(PaymentLink.PayKind.URL, "https://gateway.example.test/submit.php?x=1");
        assertThat(PaymentLinkPolicy.allowed(PaymentEnvironment.PRODUCTION, url)).isTrue();
        assertThat(PaymentLinkPolicy.allowed(PaymentEnvironment.PRODUCTION, PaymentLink.testPayment())).isFalse();
        assertThat(PaymentLinkPolicy.allowed(PaymentEnvironment.PRODUCTION,
                new PaymentLink(PaymentLink.PayKind.URL, " "))).isFalse();
        assertThat(PaymentLinkPolicy.allowed(PaymentEnvironment.PRODUCTION, null)).isFalse();
    }

    @Test
    void testPaymentInstructionOnlyInNonProductionEnvironments() {
        assertThat(PaymentLinkPolicy.allowed(PaymentEnvironment.TEST, PaymentLink.testPayment())).isTrue();
        assertThat(PaymentLinkPolicy.allowed(PaymentEnvironment.STAGING, PaymentLink.testPayment())).isTrue();
    }
}
