package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentProviderRegistry;
import com.aurora.appserver.payment.provider.PaymentQueryResult;
import com.aurora.appserver.payment.provider.yusen.JdkYusenHttpTransport;
import com.aurora.appserver.payment.provider.yusen.YusenPaymentProvider;
import com.aurora.appserver.payment.support.FakePaymentProvider;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** I3.5.5-B1: no silent fallback to Fake; no real outbound call unless explicitly enabled. */
class PaymentNoFallbackAndNetworkDefaultsTest {

    @Test
    void t07_unconfiguredYusenNeverFallsBackToFake() {
        PaymentProperties p = new PaymentProperties();
        p.setProvider("YUSEN");
        p.setEnvironment(PaymentEnvironment.STAGING);
        List<PaymentProvider> providers = List.of(new YusenPaymentProvider(p), new FakePaymentProvider());
        PaymentProviderRegistry registry = new PaymentProviderRegistry(providers, p);

        assertThat(registry.active()).isEmpty();
        assertThatThrownBy(registry::requireActive)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PAYMENT_CONFIG_MISSING);
    }

    @Test
    void t08_networkIsDisabledByDefault() {
        PaymentProperties defaults = new PaymentProperties();
        assertThat(defaults.getYusen().isNetworkEnabled()).isFalse();

        JdkYusenHttpTransport transport = new JdkYusenHttpTransport(defaults);
        assertThatThrownBy(() -> transport.get(URI.create("https://pay-gateway.example.com/api.php"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void t08_configuredYusenWithNetworkDisabledReportsInconclusiveWithoutConnecting() {
        PaymentProperties p = new PaymentProperties();
        p.getYusen().setGatewayBaseUrl("https://pay-gateway.example.com");
        p.getYusen().setPid("1001");
        p.getYusen().setKey("fictional-key-for-unit-tests");
        p.getYusen().setNotifyUrl("https://api.example.com/notify");
        p.getYusen().setReturnUrl("https://api.example.com/return");

        PaymentQueryResult result = new YusenPaymentProvider(p).queryOrder("CV20261110000000AAAAAAAAAAAA");
        assertThat(result.outcome()).isEqualTo(PaymentQueryResult.Outcome.ERROR);
        assertThat(result.isConclusive()).isFalse();
        assertThat(result.providerMsg()).isEqualTo("transport_IllegalStateException");
    }
}
