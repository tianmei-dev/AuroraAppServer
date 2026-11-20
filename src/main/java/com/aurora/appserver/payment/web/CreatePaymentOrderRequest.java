package com.aurora.appserver.payment.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Client chooses plan and checkout channel only. Amount / duration / userId are never trusted
 * from the body — they are snapshotted server-side from {@code membership_plans}.
 */
public record CreatePaymentOrderRequest(
        @NotBlank String planCode,
        @NotBlank String clientRequestId,
        @NotBlank String payChannel
) {
}
