package com.aurora.appserver.payment.provider;

/**
 * Payment instruction handed to the client.
 *
 * <ul>
 *   <li>URL — signed Yusen cashier link (the only kind allowed in PRODUCTION). Never log it.</li>
 *   <li>TEST — isolated test environment only: no link; the client shows a test-payment hint and the
 *       payment result is simulated server-side.</li>
 * </ul>
 */
public record PaymentLink(PayKind kind, String value) {

    public enum PayKind {
        URL,
        TEST
    }

    public static PaymentLink testPayment() {
        return new PaymentLink(PayKind.TEST, null);
    }

    @Override
    public String toString() {
        return "PaymentLink{kind=" + kind + ", value=<redacted>}";
    }
}
