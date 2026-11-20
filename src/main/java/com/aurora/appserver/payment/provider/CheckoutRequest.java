package com.aurora.appserver.payment.provider;

/**
 * @param yusenType optional Yusen {@code type} ({@code wxpay}/{@code alipay}); null keeps the
 *                  historical unified cashier (no type) for legacy rows without a stored channel.
 */
public record CheckoutRequest(
        String orderNo,
        int amountFen,
        String subject,
        String yusenType
) {
}
