package com.aurora.appserver.payment.config;

import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.provider.PaymentProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fails application startup when payment configuration could mix test and production:
 * PRODUCTION never runs with a FAKE provider bean or non-HTTPS Yusen endpoints, and a
 * selected provider must exist and be fully configured.
 */
@Component
public class PaymentConfigurationGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfigurationGuard.class);

    private final PaymentProperties properties;
    private final List<PaymentProvider> providers;

    public PaymentConfigurationGuard(PaymentProperties properties, List<PaymentProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties, providers);
        log.info("Payment configuration OK provider={} environment={}",
                properties.getProvider(), properties.getEnvironment());
    }

    public static void validate(PaymentProperties properties, List<PaymentProvider> providers) {
        String selected = properties.getProvider();
        PaymentEnvironment environment = properties.getEnvironment();
        if (environment == null) {
            throw new IllegalStateException("aurora.payment.environment must be set");
        }

        if (environment == PaymentEnvironment.PRODUCTION
                && providers.stream().anyMatch(p -> p.code() == PaymentProviderCode.FAKE)) {
            throw new IllegalStateException("FAKE payment provider must not be present in PRODUCTION");
        }

        if (PaymentProperties.PROVIDER_NONE.equals(selected)) {
            return;
        }

        PaymentProviderCode code;
        try {
            code = PaymentProviderCode.valueOf(selected);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown aurora.payment.provider: " + selected);
        }

        if (environment == PaymentEnvironment.PRODUCTION && code != PaymentProviderCode.YUSEN) {
            throw new IllegalStateException("PRODUCTION payment provider must be YUSEN");
        }

        Optional<PaymentProvider> provider = providers.stream().filter(p -> p.code() == code).findFirst();
        if (provider.isEmpty()) {
            throw new IllegalStateException("Selected payment provider is not available: " + code);
        }
        if (!provider.get().isConfigured()) {
            throw new IllegalStateException("Selected payment provider is not fully configured: " + code);
        }

        if (environment == PaymentEnvironment.PRODUCTION) {
            PaymentProperties.Yusen yusen = properties.getYusen();
            requireHttps("gateway-base-url", yusen.getGatewayBaseUrl());
            requireHttps("notify-url", yusen.getNotifyUrl());
            requireHttps("return-url", yusen.getReturnUrl());
        }
    }

    private static void requireHttps(String name, String url) {
        if (url == null || !url.trim().toLowerCase().startsWith("https://")) {
            throw new IllegalStateException("PRODUCTION Yusen " + name + " must use https");
        }
    }
}
