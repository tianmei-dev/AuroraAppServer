package com.aurora.appserver.payment.provider;

public record CheckoutRequest(
        String orderNo,
        int amountFen,
        String subject
) {
}
