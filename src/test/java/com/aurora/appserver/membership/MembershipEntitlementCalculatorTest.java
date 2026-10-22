package com.aurora.appserver.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.appserver.membership.domain.MembershipGrantEntity;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.service.MembershipEntitlementCalculator;
import com.aurora.appserver.membership.service.MembershipEntitlementCalculator.ComputedEntitlement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class MembershipEntitlementCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void emptyIsNone() {
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(), T0);
        assertThat(c.status()).isEqualTo(MembershipStatus.NONE);
    }

    @Test
    void firstMonthlyFromGrantTime() {
        MembershipGrantEntity g = timed(1L, PlanCode.MONTHLY, 30, T0);
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(g), T0);
        assertThat(c.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(c.startedAt()).isEqualTo(T0);
        assertThat(c.expiresAt()).isEqualTo(T0.plus(30, ChronoUnit.DAYS));
        assertThat(c.contributingPlanTypes()).containsExactly(PlanCode.MONTHLY);
    }

    @Test
    void stackMonthlyExtendsFromCurrentEnd() {
        MembershipGrantEntity g1 = timed(1L, PlanCode.MONTHLY, 30, T0);
        Instant t10 = T0.plus(10, ChronoUnit.DAYS);
        MembershipGrantEntity g2 = timed(2L, PlanCode.MONTHLY, 30, t10);
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(g1, g2), t10);
        assertThat(c.expiresAt()).isEqualTo(T0.plus(60, ChronoUnit.DAYS));
        assertThat(c.startedAt()).isEqualTo(T0);
    }

    @Test
    void monthlyPlusYearlyStacks() {
        MembershipGrantEntity monthly = timed(1L, PlanCode.MONTHLY, 30, T0);
        MembershipGrantEntity yearly = timed(2L, PlanCode.YEARLY, 365, T0.plus(1, ChronoUnit.DAYS));
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(monthly, yearly), T0);
        assertThat(c.expiresAt()).isEqualTo(T0.plus(30 + 365, ChronoUnit.DAYS));
        assertThat(c.contributingPlanTypes()).containsExactly(PlanCode.MONTHLY, PlanCode.YEARLY);
        assertThat(MembershipEntitlementCalculator.singlePlanTypeOrNull(c.contributingPlanTypes(), false))
                .isNull();
    }

    @Test
    void afterExpiryNewPurchaseStartsFresh() {
        MembershipGrantEntity g1 = timed(1L, PlanCode.MONTHLY, 30, T0);
        Instant afterExpiry = T0.plus(40, ChronoUnit.DAYS);
        MembershipGrantEntity g2 = timed(2L, PlanCode.MONTHLY, 30, afterExpiry);
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(g1, g2), afterExpiry);
        assertThat(c.startedAt()).isEqualTo(afterExpiry);
        assertThat(c.expiresAt()).isEqualTo(afterExpiry.plus(30, ChronoUnit.DAYS));
        assertThat(c.status()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void lifetimeWinsOverTimed() {
        MembershipGrantEntity monthly = timed(1L, PlanCode.MONTHLY, 30, T0);
        MembershipGrantEntity life = lifetime(2L, T0.plus(1, ChronoUnit.DAYS));
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(monthly, life), T0.plus(2, ChronoUnit.DAYS));
        assertThat(c.lifetime()).isTrue();
        assertThat(c.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(c.expiresAt()).isNull();
        assertThat(c.contributingPlanTypes()).containsExactly(PlanCode.LIFETIME);
    }

    @Test
    void expiredWhenPastEnd() {
        MembershipGrantEntity g = timed(1L, PlanCode.MONTHLY, 30, T0);
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(g), T0.plus(30, ChronoUnit.DAYS));
        assertThat(c.status()).isEqualTo(MembershipStatus.EXPIRED);
    }

    @Test
    void allRevokedIsRevoked() {
        MembershipGrantEntity g = timed(1L, PlanCode.MONTHLY, 30, T0);
        g.revoke(T0.plus(1, ChronoUnit.DAYS), "refund");
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(g), T0.plus(2, ChronoUnit.DAYS));
        assertThat(c.status()).isEqualTo(MembershipStatus.REVOKED);
    }

    @Test
    void revokeFirstOfStackRecalculatesFromRemaining() {
        MembershipGrantEntity g1 = timed(1L, PlanCode.MONTHLY, 30, T0);
        Instant t10 = T0.plus(10, ChronoUnit.DAYS);
        MembershipGrantEntity g2 = timed(2L, PlanCode.MONTHLY, 30, t10);
        g1.revoke(t10, "refund");
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(g1, g2), t10);
        assertThat(c.startedAt()).isEqualTo(t10);
        assertThat(c.expiresAt()).isEqualTo(t10.plus(30, ChronoUnit.DAYS));
        assertThat(c.activeGrantCount()).isEqualTo(1);
    }

    @Test
    void revokeLifetimeRestoresTimed() {
        MembershipGrantEntity monthly = timed(1L, PlanCode.MONTHLY, 30, T0);
        MembershipGrantEntity life = lifetime(2L, T0.plus(5, ChronoUnit.DAYS));
        life.revoke(T0.plus(6, ChronoUnit.DAYS), "refund");
        ComputedEntitlement c = MembershipEntitlementCalculator.compute(List.of(monthly, life), T0.plus(6, ChronoUnit.DAYS));
        assertThat(c.lifetime()).isFalse();
        assertThat(c.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(c.expiresAt()).isEqualTo(T0.plus(30, ChronoUnit.DAYS));
    }

    private static MembershipGrantEntity timed(long id, PlanCode code, int days, Instant grantedAt) {
        return MembershipGrantEntity.forComputation(id, code, days, false, grantedAt);
    }

    private static MembershipGrantEntity lifetime(long id, Instant grantedAt) {
        return MembershipGrantEntity.forComputation(id, PlanCode.LIFETIME, null, true, grantedAt);
    }
}
