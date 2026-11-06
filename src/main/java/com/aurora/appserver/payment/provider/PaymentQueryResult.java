package com.aurora.appserver.payment.provider;

/**
 * Provider order query outcome. NOT_CONFIRMED never means "failed": the order may still be paid later.
 */
public record PaymentQueryResult(
        Outcome outcome,
        String providerTradeNo,
        String upstreamTradeNo,
        String amountRaw,
        String channelRaw,
        String paidAtRaw
) {

    public enum Outcome {
        PAID,
        NOT_CONFIRMED,
        ERROR,
        TIMEOUT
    }

    public static PaymentQueryResult notConfirmed() {
        return new PaymentQueryResult(Outcome.NOT_CONFIRMED, null, null, null, null, null);
    }
}
