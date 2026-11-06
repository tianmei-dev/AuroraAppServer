package com.aurora.appserver.payment.provider;

import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PaymentProviderRegistry {

    private final List<PaymentProvider> providers;
    private final PaymentProperties properties;

    public PaymentProviderRegistry(List<PaymentProvider> providers, PaymentProperties properties) {
        this.providers = List.copyOf(providers);
        this.properties = properties;
    }

    public Optional<PaymentProvider> find(PaymentProviderCode code) {
        return providers.stream().filter(p -> p.code() == code).findFirst();
    }

    /** The provider selected by configuration, if it exists and is fully configured. */
    public Optional<PaymentProvider> active() {
        if (PaymentProperties.PROVIDER_NONE.equals(properties.getProvider())) {
            return Optional.empty();
        }
        PaymentProviderCode code;
        try {
            code = PaymentProviderCode.valueOf(properties.getProvider());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return find(code).filter(PaymentProvider::isConfigured);
    }

    public PaymentProvider requireActive() {
        return active().orElseThrow(() -> new BusinessException(ApiErrorCode.PAYMENT_CONFIG_MISSING));
    }
}
