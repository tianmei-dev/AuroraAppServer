package com.aurora.appserver.payment.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Only planCode and clientRequestId are accepted. Any amount, userId, channel or duration
 * fields sent by the client are ignored during deserialization.
 */
public record CreatePaymentOrderRequest(
        @NotBlank String planCode,
        @NotBlank String clientRequestId
) {
}
