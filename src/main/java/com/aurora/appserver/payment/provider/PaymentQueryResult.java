package com.aurora.appserver.payment.provider;

import java.util.Optional;

/**
 * Provider order query outcome.
 *
 * <ul>
 *   <li>PAID — provider answered authoritatively that the order is paid (still requires checks).</li>
 *   <li>NOT_CONFIRMED — provider answered authoritatively that the order is not paid (yet).</li>
 *   <li>ERROR / TIMEOUT — inconclusive. Never treat as "payment failed".</li>
 * </ul>
 */
public record PaymentQueryResult(
        Outcome outcome,
        String orderNo,
        String merchantPid,
        String providerTradeNo,
        String upstreamTradeNo,
        String amountRaw,
        Optional<Integer> amountFen,
        String channelRaw,
        String paidAtRaw,
        Integer httpStatus,
        String providerCode,
        String providerMsg
) {

    public enum Outcome {
        PAID,
        NOT_CONFIRMED,
        ERROR,
        TIMEOUT
    }

    public boolean isConclusive() {
        return outcome == Outcome.PAID || outcome == Outcome.NOT_CONFIRMED;
    }

    public static PaymentQueryResult notConfirmed(String orderNo) {
        return new PaymentQueryResult(Outcome.NOT_CONFIRMED, orderNo, null, null, null, null,
                Optional.empty(), null, null, null, null, null);
    }

    public static PaymentQueryResult inconclusive(Outcome outcome, Integer httpStatus, String providerCode, String msg) {
        if (outcome != Outcome.ERROR && outcome != Outcome.TIMEOUT) {
            throw new IllegalArgumentException("Inconclusive outcome must be ERROR or TIMEOUT");
        }
        return new PaymentQueryResult(outcome, null, null, null, null, null,
                Optional.empty(), null, null, httpStatus, providerCode, msg);
    }
}
