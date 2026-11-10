package com.aurora.appserver.payment.alert;

public enum PaymentAlertType {
    NOTIFY_REJECTED(Severity.WARN),
    NOTIFY_UNKNOWN_ORDER(Severity.ERROR),
    NOTIFY_MERCHANT_MISMATCH(Severity.ERROR),
    NOTIFY_AMOUNT_MISMATCH(Severity.ERROR),
    QUERY_MISMATCH(Severity.ERROR),
    PROVIDER_TRADE_NO_CONFLICT(Severity.ERROR),
    LATE_PAYMENT(Severity.WARN),
    FULFILLMENT_FAILING(Severity.ERROR),
    PAYMENT_LINK_WITHHELD(Severity.ERROR);

    public enum Severity {
        WARN,
        ERROR
    }

    private final Severity severity;

    PaymentAlertType(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
