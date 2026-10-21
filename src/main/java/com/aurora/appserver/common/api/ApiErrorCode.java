package com.aurora.appserver.common.api;

/**
 * Stable, extensible API error codes.
 * Ranges are reserved for future modules:
 * 0        — success
 * 40000+   — client / application context errors
 * 40100+   — authentication (S2+)
 * 40300+   — authorization (S2+)
 * 40400+   — resource not found
 * 50000+   — server errors
 */
public enum ApiErrorCode {

    SUCCESS(0, "success"),

    BAD_REQUEST(40000, "Bad request"),
    APP_NOT_FOUND(40001, "Application not found"),
    APP_DISABLED(40002, "Application is disabled"),
    APP_CONTEXT_REQUIRED(40003, "Application context is required"),
    VALIDATION_FAILED(40004, "Validation failed"),

    UNAUTHORIZED(40100, "Unauthorized"),
    FORBIDDEN(40300, "Forbidden"),
    NOT_FOUND(40400, "Resource not found"),

    INTERNAL_ERROR(50000, "Internal server error");

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
