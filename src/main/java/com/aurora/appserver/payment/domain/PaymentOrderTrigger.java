package com.aurora.appserver.payment.domain;

public enum PaymentOrderTrigger {
    CREATE,
    REUSE,
    NOTIFY,
    QUERY,
    EXPIRE_JOB,
    FULFILL,
    RECOVERY
}
