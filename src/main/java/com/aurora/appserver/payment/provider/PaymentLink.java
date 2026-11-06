package com.aurora.appserver.payment.provider;

/**
 * Checkout link handed to the client. The value is a signed URL: never log it.
 */
public record PaymentLink(PayKind kind, String value) {

    public enum PayKind {
        URL
    }

    @Override
    public String toString() {
        return "PaymentLink{kind=" + kind + ", value=<redacted>}";
    }
}
