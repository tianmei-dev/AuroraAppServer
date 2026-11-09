package com.aurora.appserver.payment.service;

/** Payment -> membership boundary: grant the paid order's snapshot, idempotently. */
public interface MembershipFulfillmentPort {

    /** @return membership grant id (existing one when already granted) */
    Long grantForPaidOrder(Long appId, String orderNo);
}
