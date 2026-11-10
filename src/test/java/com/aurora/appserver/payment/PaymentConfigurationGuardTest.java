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

    // ---------------------------------------------------------------- I3.5.5-B1 additions

    @Test
    void t04_stagingRejectsFakeBeanAndFakeSelection() {
        PaymentProperties none = props("NONE", PaymentEnvironment.STAGING);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(
                none, List.of(new YusenPaymentProvider(none), new FakePaymentProvider())))
                .hasMessageContaining("FAKE payment provider must not be present in STAGING");

        PaymentProperties fake = props("FAKE", PaymentEnvironment.STAGING);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(fake, List.of(new YusenPaymentProvider(fake))))
                .hasMessageContaining("STAGING payment provider must be YUSEN");
    }

    @Test
    void t06_yusenRequiresHttpsInEveryEnvironment() {
        for (PaymentEnvironment env : PaymentEnvironment.values()) {
            PaymentProperties http = props("YUSEN", env);
            configureYusen(http, "http");
            assertThatThrownBy(() -> PaymentConfigurationGuard.validate(http, List.of(new YusenPaymentProvider(http))))
                    .as(env.name())
                    .hasMessageContaining("must use https");
        }
        PaymentProperties staging = props("YUSEN", PaymentEnvironment.STAGING);
        configureYusen(staging, "https");
        assertThatCode(() -> PaymentConfigurationGuard.validate(staging, List.of(new YusenPaymentProvider(staging))))
                .doesNotThrowAnyException();
    }

    @Test
    void t07_stagingYusenWithoutMerchantSettingsCannotStart() {
        PaymentProperties p = props("YUSEN", PaymentEnvironment.STAGING);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(p, List.of(new YusenPaymentProvider(p))))
                .hasMessageContaining("not fully configured");
    }

    @Test
    void t08_networkAccessOnlyForConfiguredYusenOutsideTest() {
        PaymentProperties test = props("FAKE", PaymentEnvironment.TEST);
        test.getYusen().setNetworkEnabled(true);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(
                test, List.of(new YusenPaymentProvider(test), new FakePaymentProvider())))
                .hasMessageContaining("must not be enabled in TEST");

        PaymentProperties none = props("NONE", PaymentEnvironment.STAGING);
        none.getYusen().setNetworkEnabled(true);
        assertThatThrownBy(() -> PaymentConfigurationGuard.validate(none, List.of(new YusenPaymentProvider(none))))
                .hasMessageContaining("requires the YUSEN provider");

        PaymentProperties staging = props("YUSEN", PaymentEnvironment.STAGING);
        configureYusen(staging, "https");
        staging.getYusen().setNetworkEnabled(true);
        assertThatCode(() -> PaymentConfigurationGuard.validate(staging, List.of(new YusenPaymentProvider(staging))))
                .doesNotThrowAnyException();
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
