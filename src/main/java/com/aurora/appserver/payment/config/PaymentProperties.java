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
    /** Max time the notify endpoint waits for its triggered query before acknowledging. */
    private Duration notifyConfirmWait = Duration.ofSeconds(3);
    private final Yusen yusen = new Yusen();
    private final Jobs jobs = new Jobs();

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

    public Duration getNotifyConfirmWait() {
        return notifyConfirmWait;
    }

    public void setNotifyConfirmWait(Duration notifyConfirmWait) {
        this.notifyConfirmWait = notifyConfirmWait;
    }

    public Yusen getYusen() {
        return yusen;
    }

    public Jobs getJobs() {
        return jobs;
    }

    /** Background confirmation / fulfillment / expiry settings. */
    public static class Jobs {
        private boolean enabled = true;
        private long intervalMs = 30_000L;
        private Duration pendingQueryInterval = Duration.ofMinutes(2);
        private Duration latePatrolWindow = Duration.ofHours(24);
        private Duration latePatrolInterval = Duration.ofMinutes(10);
        private Duration notificationRetryInterval = Duration.ofMinutes(2);
        private Duration notificationRetryWindow = Duration.ofDays(7);
        /** Expire locally after this long past expiry when every query was inconclusive. */
        private Duration inconclusiveExpireGrace = Duration.ofMinutes(30);
        private int inconclusiveMinAttempts = 3;
        private Duration fulfillLease = Duration.ofMinutes(2);
        private int fulfillAlertAttempts = 5;
        private int batchSize = 100;
        private int maxPerCycle = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public Duration getPendingQueryInterval() {
            return pendingQueryInterval;
        }

        public void setPendingQueryInterval(Duration pendingQueryInterval) {
            this.pendingQueryInterval = pendingQueryInterval;
        }

        public Duration getLatePatrolWindow() {
            return latePatrolWindow;
        }

        public void setLatePatrolWindow(Duration latePatrolWindow) {
            this.latePatrolWindow = latePatrolWindow;
        }

        public Duration getLatePatrolInterval() {
            return latePatrolInterval;
        }

        public void setLatePatrolInterval(Duration latePatrolInterval) {
            this.latePatrolInterval = latePatrolInterval;
        }

        public Duration getNotificationRetryInterval() {
            return notificationRetryInterval;
        }

        public void setNotificationRetryInterval(Duration notificationRetryInterval) {
            this.notificationRetryInterval = notificationRetryInterval;
        }

        public Duration getNotificationRetryWindow() {
            return notificationRetryWindow;
        }

        public void setNotificationRetryWindow(Duration notificationRetryWindow) {
            this.notificationRetryWindow = notificationRetryWindow;
        }

        public Duration getInconclusiveExpireGrace() {
            return inconclusiveExpireGrace;
        }

        public void setInconclusiveExpireGrace(Duration inconclusiveExpireGrace) {
            this.inconclusiveExpireGrace = inconclusiveExpireGrace;
        }

        public int getInconclusiveMinAttempts() {
            return inconclusiveMinAttempts;
        }

        public void setInconclusiveMinAttempts(int inconclusiveMinAttempts) {
            this.inconclusiveMinAttempts = inconclusiveMinAttempts;
        }

        public Duration getFulfillLease() {
            return fulfillLease;
        }

        public void setFulfillLease(Duration fulfillLease) {
            this.fulfillLease = fulfillLease;
        }

        public int getFulfillAlertAttempts() {
            return fulfillAlertAttempts;
        }

        public void setFulfillAlertAttempts(int fulfillAlertAttempts) {
            this.fulfillAlertAttempts = fulfillAlertAttempts;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxPerCycle() {
            return maxPerCycle;
        }

        public void setMaxPerCycle(int maxPerCycle) {
            this.maxPerCycle = maxPerCycle;
        }
    }

    public static class Yusen {
        private String gatewayBaseUrl = "";
        private String pid = "";
        private String key = "";
        private String notifyUrl = "";
        private String returnUrl = "";
        /** Real outbound HTTP is refused unless explicitly enabled. */
        private boolean networkEnabled = false;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(5);

        public boolean isNetworkEnabled() {
            return networkEnabled;
        }

        public void setNetworkEnabled(boolean networkEnabled) {
            this.networkEnabled = networkEnabled;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

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
                    + ", notifyUrl=" + notifyUrl + ", returnUrl=" + returnUrl
                    + ", networkEnabled=" + networkEnabled + "}";
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
