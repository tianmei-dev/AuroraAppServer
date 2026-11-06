package com.aurora.appserver.payment.config;

import com.aurora.appserver.payment.domain.PaymentEnvironment;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment settings. Merchant credentials must come from environment variables or
 * gitignored local config — never from the database, Android, or committed files.
 */
@ConfigurationProperties(prefix = "aurora.payment")
public class PaymentProperties {

    public static final String PROVIDER_NONE = "NONE";

    /** NONE | YUSEN | FAKE. NONE disables order creation. */
    private String provider = PROVIDER_NONE;
    private PaymentEnvironment environment = PaymentEnvironment.PRODUCTION;
    private Duration orderTtl = Duration.ofMinutes(30);
    /** A pending order is reused only if at least this much time remains before expiry. */
    private Duration reuseMinRemaining = Duration.ofMinutes(5);
    private String orderNoPrefix = "CV";
    private final Yusen yusen = new Yusen();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null || provider.isBlank() ? PROVIDER_NONE : provider.trim().toUpperCase();
    }

    public PaymentEnvironment getEnvironment() {
        return environment;
    }

    public void setEnvironment(PaymentEnvironment environment) {
        this.environment = environment;
    }

    public Duration getOrderTtl() {
        return orderTtl;
    }

    public void setOrderTtl(Duration orderTtl) {
        this.orderTtl = orderTtl;
    }

    public Duration getReuseMinRemaining() {
        return reuseMinRemaining;
    }

    public void setReuseMinRemaining(Duration reuseMinRemaining) {
        this.reuseMinRemaining = reuseMinRemaining;
    }

    public String getOrderNoPrefix() {
        return orderNoPrefix;
    }

    public void setOrderNoPrefix(String orderNoPrefix) {
        this.orderNoPrefix = orderNoPrefix;
    }

    public Yusen getYusen() {
        return yusen;
    }

    public static class Yusen {
        private String gatewayBaseUrl = "";
        private String pid = "";
        private String key = "";
        private String notifyUrl = "";
        private String returnUrl = "";

        public String getGatewayBaseUrl() {
            return gatewayBaseUrl;
        }

        public void setGatewayBaseUrl(String gatewayBaseUrl) {
            this.gatewayBaseUrl = gatewayBaseUrl;
        }

        public String getPid() {
            return pid;
        }

        public void setPid(String pid) {
            this.pid = pid;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getNotifyUrl() {
            return notifyUrl;
        }

        public void setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl;
        }

        public String getReturnUrl() {
            return returnUrl;
        }

        public void setReturnUrl(String returnUrl) {
            this.returnUrl = returnUrl;
        }

        public boolean isComplete() {
            return hasText(gatewayBaseUrl) && hasText(pid) && hasText(key)
                    && hasText(notifyUrl) && hasText(returnUrl);
        }

        @Override
        public String toString() {
            return "Yusen{gatewayBaseUrl=" + gatewayBaseUrl + ", pid=" + pid
                    + ", key=" + (hasText(key) ? "***" : "(empty)")
                    + ", notifyUrl=" + notifyUrl + ", returnUrl=" + returnUrl + "}";
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
