package com.aurora.appserver.membership.web;

import com.aurora.appserver.common.money.FenAmounts;
import com.aurora.appserver.membership.domain.MembershipPlanEntity;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Server-defined plan catalog entry. Prices are display-only for clients; orders snapshot server values. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MembershipPlanResponse(
        String planCode,
        String displayName,
        int priceFen,
        String priceDisplay,
        Integer durationDays,
        boolean isLifetime
) {

    public static MembershipPlanResponse from(MembershipPlanEntity plan) {
        return new MembershipPlanResponse(
                plan.getPlanCode().name(),
                plan.getDisplayName(),
                plan.getPriceFen(),
                FenAmounts.toYuanString(plan.getPriceFen()),
                plan.getDurationDays(),
                plan.isLifetime());
    }
}
