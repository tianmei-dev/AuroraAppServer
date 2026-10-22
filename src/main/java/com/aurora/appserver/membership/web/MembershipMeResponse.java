package com.aurora.appserver.membership.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Membership status for the authenticated user.
 *
 * <p>{@code planType} is set only when a single plan type fully describes the effective entitlement
 * (e.g. pure MONTHLY or LIFETIME). When multiple timed plans are stacked, {@code planType} is null
 * and {@code contributingPlanTypes} lists the contributing codes — do not treat planType as the
 * only purchased plan.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MembershipMeResponse(
        String status,
        String planType,
        List<String> contributingPlanTypes,
        boolean isLifetime,
        Instant membershipStartedAt,
        Instant membershipExpiresAt,
        Instant serverTime,
        int activeGrantCount
) {
}
