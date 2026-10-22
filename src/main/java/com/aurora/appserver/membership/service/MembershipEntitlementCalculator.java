package com.aurora.appserver.membership.service;

import com.aurora.appserver.membership.domain.MembershipGrantEntity;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic entitlement replay from the grant ledger.
 *
 * <p>Timed grants are walked in {@code granted_at ASC, id ASC} order:
 * <ul>
 *   <li>If there is no current coverage, or the grant time is at/after current end → start a new
 *       coverage window from {@code granted_at}.</li>
 *   <li>Otherwise stack: extend {@code currentEnd} by the grant duration.</li>
 * </ul>
 * Any non-revoked lifetime grant wins: {@code isLifetime=true}, no expiry.
 * Revoked grants are ignored when recomputing (audit history retained).
 */
public final class MembershipEntitlementCalculator {

    private MembershipEntitlementCalculator() {
    }

    public record ComputedEntitlement(
            MembershipStatus status,
            boolean lifetime,
            Instant startedAt,
            Instant expiresAt,
            List<PlanCode> contributingPlanTypes,
            int activeGrantCount
    ) {
    }

    public static ComputedEntitlement compute(List<MembershipGrantEntity> allGrants, Instant now) {
        if (allGrants == null || allGrants.isEmpty()) {
            return new ComputedEntitlement(MembershipStatus.NONE, false, null, null, List.of(), 0);
        }

        List<MembershipGrantEntity> active = allGrants.stream()
                .filter(g -> !g.isRevoked())
                .sorted(Comparator
                        .comparing(MembershipGrantEntity::getGrantedAt)
                        .thenComparing(MembershipGrantEntity::getId))
                .toList();

        boolean hadAnyGrant = !allGrants.isEmpty();
        boolean allRevoked = active.isEmpty() && hadAnyGrant;
        if (allRevoked) {
            return new ComputedEntitlement(MembershipStatus.REVOKED, false, null, null, List.of(), 0);
        }

        List<MembershipGrantEntity> lifetimes = active.stream().filter(MembershipGrantEntity::isLifetime).toList();
        if (!lifetimes.isEmpty()) {
            Instant started = lifetimes.stream()
                    .map(MembershipGrantEntity::getGrantedAt)
                    .min(Instant::compareTo)
                    .orElse(null);
            Set<PlanCode> types = new LinkedHashSet<>();
            types.add(PlanCode.LIFETIME);
            return new ComputedEntitlement(
                    MembershipStatus.ACTIVE,
                    true,
                    started,
                    null,
                    List.copyOf(types),
                    active.size()
            );
        }

        Instant currentEnd = null;
        Instant segmentStart = null;
        Set<PlanCode> contributing = new LinkedHashSet<>();

        for (MembershipGrantEntity grant : active) {
            Duration duration = Duration.ofDays(grant.getDurationDays());
            if (currentEnd == null || !grant.getGrantedAt().isBefore(currentEnd)) {
                // Gap or first grant: open a new coverage segment from grant time.
                segmentStart = grant.getGrantedAt();
                currentEnd = segmentStart.plus(duration);
            } else {
                // Still covered: stack duration onto the current segment end.
                currentEnd = currentEnd.plus(duration);
            }
            contributing.add(grant.getPlanCode());
        }

        if (currentEnd == null) {
            return new ComputedEntitlement(MembershipStatus.NONE, false, null, null, List.of(), 0);
        }

        boolean activeNow = now.isBefore(currentEnd);
        MembershipStatus status = activeNow ? MembershipStatus.ACTIVE : MembershipStatus.EXPIRED;
        return new ComputedEntitlement(
                status,
                false,
                segmentStart,
                currentEnd,
                List.copyOf(contributing),
                active.size()
        );
    }

    /** Plan type for API when exactly one contributing type; otherwise null (stacked / mixed). */
    public static String singlePlanTypeOrNull(List<PlanCode> contributingPlanTypes, boolean lifetime) {
        if (lifetime) {
            return PlanCode.LIFETIME.name();
        }
        if (contributingPlanTypes == null || contributingPlanTypes.size() != 1) {
            return null;
        }
        return contributingPlanTypes.getFirst().name();
    }

    public static List<String> planTypeNames(List<PlanCode> contributingPlanTypes) {
        if (contributingPlanTypes == null || contributingPlanTypes.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(contributingPlanTypes.size());
        for (PlanCode code : contributingPlanTypes) {
            names.add(code.name());
        }
        return List.copyOf(names);
    }
}
