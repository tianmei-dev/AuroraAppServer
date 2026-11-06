package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.payment.config.PaymentConfigurationGuard;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.yusen.YusenPaymentProvider;
import com.aurora.appserver.payment.support.FakePaymentProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentConfigurationGuardTest {

    private static PaymentProperties props(String provider, PaymentEnvironment env) {
        PaymentProperties p = new PaymentProperties();
        p.setProvider(provider);
        p.setEnvironment(env);
        return p;
    }

    private static void configureYusen(PaymentProperties p, String scheme) {
        p.getYusen().setGatewayBaseUrl(scheme + "://gateway.example.test");
        p.getYusen().setPid("1001");
        p.getYusen().setKey("fictional-merchant-key-0001");
        p.getYusen().setNotifyUrl(scheme + "://api.example.test/notify");
        p.getYusen().setReturnUrl(scheme + "://api.example.test/return");
    }

    @Test
    void productionRejectsFakeProviderBeanEvenWhenNotSelected() {
        PaymentProperties p = props("NONE", PaymentEnvironment.PRODUCTION);
        List<PaymentProvider> providers = List.of(new YusenPaymentProvider(p), new FakePaymentProvider());
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(p, providers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAKE");
    }

    @Test
    void productionRejectsSelectingFake() {
        PaymentProperties p = props("FAKE", PaymentEnvironment.PRODUCTION);
        List<PaymentProvider> providers = List.of(new YusenPaymentProvider(p));
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(p, providers))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionYusenRequiresCompleteHttpsConfiguration() {
        PaymentProperties missing = props("YUSEN", PaymentEnvironment.PRODUCTION);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(
                missing, List.of(new YusenPaymentProvider(missing))))
                .hasMessageContaining("not fully configured");

        PaymentProperties http = props("YUSEN", PaymentEnvironment.PRODUCTION);
        configureYusen(http, "http");
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(
                http, List.of(new YusenPaymentProvider(http))))
                .hasMessageContaining("https");

        PaymentProperties ok = props("YUSEN", PaymentEnvironment.PRODUCTION);
        configureYusen(ok, "https");
        assertThatCode(() -> PaymentConfigurationGuard.validate(ok, List.of(new YusenPaymentProvider(ok))))
                .doesNotThrowAnyException();
    }

    @Test
    void defaultNoneIsAllowedEverywhere() {
        PaymentProperties p = new PaymentProperties();
        assertThatCode(() -> PaymentConfigurationGuard.validate(p, List.of(new YusenPaymentProvider(p))))
                .doesNotThrowAnyException();
    }

    @Test
    void testEnvironmentAllowsFake() {
        PaymentProperties p = props("FAKE", PaymentEnvironment.TEST);
        assertThatCode(() -> PaymentConfigurationGuard.validate(
                p, List.of(new YusenPaymentProvider(p), new FakePaymentProvider())))
                .doesNotThrowAnyException();
    }

    @Test
    void selectedProviderMustExistAndUnknownValuesFail() {
        PaymentProperties fakeMissing = props("FAKE", PaymentEnvironment.TEST);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(
                fakeMissing, List.of(new YusenPaymentProvider(fakeMissing))))
                .hasMessageContaining("not available");

        PaymentProperties unknown = props("ALIPAY", PaymentEnvironment.TEST);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(unknown, List.of()))
                .hasMessageContaining("Unknown");
    }
}
