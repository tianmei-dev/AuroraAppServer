package com.aurora.appserver.common.exception;

import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ApiErrorCode errorCode = ex.getErrorCode();
        HttpStatus status = mapHttpStatus(errorCode);
        return ResponseEntity.status(status).body(ApiResponse.failure(errorCode, ex.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ApiErrorCode.VALIDATION_FAILED, ApiErrorCode.VALIDATION_FAILED.getDefaultMessage()));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(ApiErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure(ApiErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure(ApiErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ApiErrorCode.INTERNAL_ERROR));
    }

    private static HttpStatus mapHttpStatus(ApiErrorCode errorCode) {
        return switch (errorCode) {
            case APP_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case APP_DISABLED, FORBIDDEN, APP_CONTEXT_MISMATCH -> HttpStatus.FORBIDDEN;
            case APP_CONTEXT_REQUIRED, BAD_REQUEST, VALIDATION_FAILED,
                    WECHAT_CODE_INVALID, WECHAT_AUTH_FAILED, DEVICE_KEY_INVALID -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED, SESSION_EXPIRED, SESSION_REVOKED -> HttpStatus.UNAUTHORIZED;
            case TRIAL_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case WECHAT_CONFIG_MISSING, TRIAL_CONFIG_MISSING, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SUCCESS -> HttpStatus.OK;
        };
    }
}
