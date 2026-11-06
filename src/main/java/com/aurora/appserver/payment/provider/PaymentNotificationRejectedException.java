package com.aurora.appserver.payment.provider;

public class PaymentNotificationRejectedException extends RuntimeException {

    public enum Reason {
        PID_MISMATCH,
        SIGN_INVALID,
        MISSING_FIELDS
    }

    private final Reason reason;

    public PaymentNotificationRejectedException(Reason reason) {
        super("Payment notification rejected: " + reason);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
