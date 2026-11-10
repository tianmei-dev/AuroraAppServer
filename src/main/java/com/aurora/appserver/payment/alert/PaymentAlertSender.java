package com.aurora.appserver.payment.alert;

/** Outbound alert transport. Implementations must never log the destination URL. */
public interface PaymentAlertSender {

    boolean isEnabled();

    void send(String jsonPayload) throws Exception;
}
