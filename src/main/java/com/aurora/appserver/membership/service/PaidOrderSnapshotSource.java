package com.aurora.appserver.membership.service;

import com.aurora.appserver.membership.domain.PlanCode;
import java.util.Optional;

/**
 * Port implemented by the payment module. Returns a snapshot only for orders the server has
 * itself confirmed as paid (PAID or FULFILLED); anything else yields empty.
 */
public interface PaidOrderSnapshotSource {

    Optional<PaidOrderSnapshot> findConfirmed(Long appId, String orderNo);

    record PaidOrderSnapshot(
            Long appId,
            Long userId,
            String orderNo,
            Long planId,
            PlanCode planCode,
            Integer durationDays,
            boolean lifetime
    ) {
    }
}
