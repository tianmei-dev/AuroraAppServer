package com.aurora.appserver.payment.domain;

/** Order-level anomalies recorded for operator follow-up. Never grant membership on a mismatch. */
public enum PaymentAnomaly {
    LATE_PAYMENT,
    NOTIFY_AMOUNT_MISMATCH,
    NOTIFY_MERCHANT_MISMATCH,
    QUERY_AMOUNT_MISMATCH,
    QUERY_MERCHANT_MISMATCH,
    QUERY_ORDER_MISMATCH,
    EXPIRED_INCONCLUSIVE
}
