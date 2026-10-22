package com.aurora.appserver.common.api;

/**
 * Stable, extensible API error codes.
 * Ranges are reserved for future modules:
 * 0        — success
 * 40000+   — client / application context errors
 * 40100+   — authentication (S2+)
 * 40300+   — authorization (S2+)
 * 40400+   — resource not found
 * 42900+   — rate limiting
 * 50000+   — server errors
 */
public enum ApiErrorCode {

    SUCCESS(0, "success"),

    BAD_REQUEST(40000, "Bad request"),
    APP_NOT_FOUND(40001, "Application not found"),
    APP_DISABLED(40002, "Application is disabled"),
    APP_CONTEXT_REQUIRED(40003, "Application context is required"),
    VALIDATION_FAILED(40004, "Validation failed"),
    WECHAT_CODE_INVALID(40005, "WeChat authorization code is invalid"),
    WECHAT_AUTH_FAILED(40006, "WeChat authentication failed"),
    DEVICE_KEY_INVALID(40007, "Device key is invalid"),
    MEMBERSHIP_PLAN_DISABLED(40008, "Membership plan is disabled"),
    MEMBERSHIP_GRANT_SOURCE_INVALID(40009, "Membership grant source is invalid"),

    UNAUTHORIZED(40100, "Unauthorized"),
    SESSION_EXPIRED(40101, "Session expired"),
    SESSION_REVOKED(40102, "Session revoked"),

    FORBIDDEN(40300, "Forbidden"),
    APP_CONTEXT_MISMATCH(40301, "Application context mismatch"),
    MEMBERSHIP_TEST_SOURCE_FORBIDDEN(40302, "TEST membership grants are not allowed in this environment"),

    NOT_FOUND(40400, "Resource not found"),
    MEMBERSHIP_PLAN_NOT_FOUND(40401, "Membership plan not found"),
    MEMBERSHIP_GRANT_NOT_FOUND(40402, "Membership grant not found"),

    TRIAL_RATE_LIMITED(42901, "Too many trial requests"),

    INTERNAL_ERROR(50000, "Internal server error"),
    WECHAT_CONFIG_MISSING(50001, "WeChat configuration is missing"),
    TRIAL_CONFIG_MISSING(50002, "Trial HMAC configuration is missing");

    private final int code;
    private final String defaultMessage;

    ApiErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
