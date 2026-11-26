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
    TRIAL_CAP_INVALID(40010, "Trial remainingSecondsCap is invalid"),
    PAYMENT_CLIENT_REQUEST_CONFLICT(40011, "clientRequestId was already used for a different plan"),
    PAYMENT_LIFETIME_ALREADY_ACTIVE(40012, "Lifetime membership is already active"),
    PAYMENT_ORDER_NOT_CONFIRMED(40013, "Payment order is not confirmed as paid"),
    PAYMENT_CHANNEL_INVALID(40014, "Payment channel is missing or unsupported"),
    PAYMENT_CHANNEL_CONFLICT(40015, "clientRequestId was already used for a different payment channel"),
    ACCOUNT_DELETE_PENDING_ORDER(40016, "请等待当前待支付订单过期后再注销账号"),
    ACCOUNT_DELETE_PAID_UNFULFILLED(40017, "存在已支付未完成履约的订单，请联系人工客服处理：594841675@qq.com"),
    ADMIN_AUTH_FAILED(40018, "Admin username or password is incorrect"),
    ADMIN_DISABLED(40019, "Admin account is disabled"),
    ADMIN_NOT_CONFIGURED(40020, "Admin login is not configured for this application"),

    UNAUTHORIZED(40100, "Unauthorized"),
    SESSION_EXPIRED(40101, "Session expired"),
    SESSION_REVOKED(40102, "Session revoked"),

    FORBIDDEN(40300, "Forbidden"),
    APP_CONTEXT_MISMATCH(40301, "Application context mismatch"),
    MEMBERSHIP_TEST_SOURCE_FORBIDDEN(40302, "TEST membership grants are not allowed in this environment"),

    NOT_FOUND(40400, "Resource not found"),
    MEMBERSHIP_PLAN_NOT_FOUND(40401, "Membership plan not found"),
    MEMBERSHIP_GRANT_NOT_FOUND(40402, "Membership grant not found"),
    PAYMENT_ORDER_NOT_FOUND(40403, "Payment order not found"),

    TRIAL_RATE_LIMITED(42901, "Too many trial requests"),
    ADMIN_LOGIN_RATE_LIMITED(42902, "Too many failed admin login attempts"),

    INTERNAL_ERROR(50000, "Internal server error"),
    WECHAT_CONFIG_MISSING(50001, "WeChat configuration is missing"),
    TRIAL_CONFIG_MISSING(50002, "Trial HMAC configuration is missing"),
    PAYMENT_CONFIG_MISSING(50003, "Payment configuration is missing");

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
