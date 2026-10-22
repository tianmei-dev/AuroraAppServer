package com.aurora.appserver.auth.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aurora")
public class AuroraAuthProperties {

    private final Auth auth = new Auth();
    private final Wechat wechat = new Wechat();

    public Auth getAuth() {
        return auth;
    }

    public Wechat getWechat() {
        return wechat;
    }

    public static class Auth {
        /** Session lifetime; default 7 days. */
        private Duration sessionTtl = Duration.ofDays(7);

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }
    }

    public static class Wechat {
        /**
         * Keyed by app_code (e.g. calculator_vault).
         * Secrets come from env / local config — never store plaintext secrets in DB.
         */
        private Map<String, AppCredentials> apps = new LinkedHashMap<>();

        public Map<String, AppCredentials> getApps() {
            return apps;
        }

        public void setApps(Map<String, AppCredentials> apps) {
            this.apps = apps != null ? apps : new LinkedHashMap<>();
        }
    }

    public static class AppCredentials {
        private String appId = "";
        private String appSecret = "";

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public boolean isComplete() {
            return appId != null && !appId.isBlank()
                    && appSecret != null && !appSecret.isBlank();
        }
    }
}
