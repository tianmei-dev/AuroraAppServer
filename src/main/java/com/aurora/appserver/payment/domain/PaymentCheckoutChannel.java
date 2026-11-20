package com.aurora.appserver.payment.domain;

import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;

/**
 * Client-selected checkout channel persisted on the order and mapped to Yusen {@code type}.
 */
public enum PaymentCheckoutChannel {
    WECHAT_PAY("wxpay"),
    ALIPAY("alipay");

    private final String yusenType;

    PaymentCheckoutChannel(String yusenType) {
        this.yusenType = yusenType;
    }

    public String yusenType() {
        return yusenType;
    }

    public static PaymentCheckoutChannel require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ApiErrorCode.PAYMENT_CHANNEL_INVALID);
        }
        try {
            return PaymentCheckoutChannel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ApiErrorCode.PAYMENT_CHANNEL_INVALID);
        }
    }
}
