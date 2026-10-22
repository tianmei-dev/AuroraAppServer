package com.aurora.appserver.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.repository.MembershipGrantRepository;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.membership.service.MembershipService.GrantCommand;
import com.aurora.appserver.membership.service.MembershipService.GrantResult;
import com.aurora.appserver.membership.service.MembershipService.MembershipStatusView;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.trial.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@Import(MembershipServiceIT.ClockConfig.class)
class MembershipServiceIT {

    private static final String APP_A = "calculator_vault";
    private static final String APP_B = "speaker_cleaner";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @TestConfiguration
    static class ClockConfig {
        static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MembershipGrantRepository grantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long appAId;
    private Long appBId;

    @BeforeEach
    void setUp() {
        ClockConfig.CLOCK.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        ensureSecondApp();
        ensureAppBPlans();
        appAId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_A);
        appBId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_B);
    }

    @Test
    void queryUserWithNoMembership() {
        Long userId = createUser(appAId);
        MembershipStatusView view = membershipService.getStatus(appAId, userId);
        assertThat(view.status()).isEqualTo(MembershipStatus.NONE);
        assertThat(view.lifetime()).isFalse();
        assertThat(view.membershipExpiresAt()).isNull();
        assertThat(view.activeGrantCount()).isZero();
    }

    @Test
    void firstMonthlyGrant() {
        Long userId = createUser(appAId);
        GrantResult result = grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("pay-monthly"));
        assertThat(result.created()).isTrue();
        assertThat(result.status().status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(result.status().planType()).isEqualTo("MONTHLY");
        assertThat(result.status().membershipStartedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.status().membershipExpiresAt()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    void firstYearlyGrant() {
        Long userId = createUser(appAId);
        MembershipStatusView view = grant(appAId, userId, PlanCode.YEARLY, uniqueRef("pay-yearly")).status();
        assertThat(view.planType()).isEqualTo("YEARLY");
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void firstLifetimeGrant() {
        Long userId = createUser(appAId);
        MembershipStatusView view = grant(appAId, userId, PlanCode.LIFETIME, uniqueRef("pay-life")).status();
        assertThat(view.lifetime()).isTrue();
        assertThat(view.planType()).isEqualTo("LIFETIME");
        assertThat(view.membershipExpiresAt()).isNull();
        assertThat(view.status()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void monthlyRepurchaseExtends() {
        Long userId = createUser(appAId);
        grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("m1"));
        ClockConfig.CLOCK.advance(Duration.ofDays(10));
        MembershipStatusView view = grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("m2")).status();
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
        assertThat(view.membershipStartedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void yearlyRepurchaseExtends() {
        Long userId = createUser(appAId);
        grant(appAId, userId, PlanCode.YEARLY, uniqueRef("y1"));
        ClockConfig.CLOCK.advance(Duration.ofDays(30));
        MembershipStatusView view = grant(appAId, userId, PlanCode.YEARLY, uniqueRef("y2")).status();
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
    }

    @Test
    void monthlyAndYearlyStack() {
        Long userId = createUser(appAId);
        grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("stack-m"));
        ClockConfig.CLOCK.advance(Duration.ofDays(1));
        MembershipStatusView view = grant(appAId, userId, PlanCode.YEARLY, uniqueRef("stack-y")).status();
        assertThat(view.planType()).isNull();
        assertThat(view.contributingPlanTypes()).containsExactly("MONTHLY", "YEARLY");
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2027-01-31T00:00:00Z"));
    }

    @Test
    void repurchaseAfterExpiryStartsFresh() {
        Long userId = createUser(appAId);
        grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("exp-m1"));
        ClockConfig.CLOCK.advance(Duration.ofDays(40));
        MembershipStatusView before = membershipService.getStatus(appAId, userId);
        assertThat(before.status()).isEqualTo(MembershipStatus.EXPIRED);

        MembershipStatusView view = grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("exp-m2")).status();
        assertThat(view.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(view.membershipStartedAt()).isEqualTo(Instant.parse("2026-02-10T00:00:00Z"));
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2026-03-12T00:00:00Z"));
    }

    @Test
    void lifetimePreferredOverTimed() {
        Long userId = createUser(appAId);
        grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("life-pref-m"));
        MembershipStatusView view = grant(appAId, userId, PlanCode.LIFETIME, uniqueRef("life-pref-l")).status();
        assertThat(view.lifetime()).isTrue();
        assertThat(view.membershipExpiresAt()).isNull();

        grant(appAId, userId, PlanCode.YEARLY, uniqueRef("life-pref-y"));
        MembershipStatusView afterTimed = membershipService.getStatus(appAId, userId);
        assertThat(afterTimed.lifetime()).isTrue();
        assertThat(afterTimed.membershipExpiresAt()).isNull();
    }

    @Test
    void sameSourceIdempotent() {
        Long userId = createUser(appAId);
        String ref = uniqueRef("idem");
        GrantResult first = grant(appAId, userId, PlanCode.MONTHLY, ref);
        GrantResult second = grant(appAId, userId, PlanCode.MONTHLY, ref);
        assertThat(second.created()).isFalse();
        assertThat(second.grantId()).isEqualTo(first.grantId());
        assertThat(grantRepository.findByAppIdAndUserIdOrderByGrantedAtAscIdAsc(appAId, userId)).hasSize(1);
        assertThat(second.status().membershipExpiresAt()).isEqualTo(first.status().membershipExpiresAt());
    }

    @Test
    void concurrentSameSourceIdempotent() throws Exception {
        Long userId = createUser(appAId);
        String ref = uniqueRef("conc-same");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        List<Future<GrantResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                GrantResult r = grant(appAId, userId, PlanCode.MONTHLY, ref);
                if (r.created()) {
                    created.incrementAndGet();
                }
                return r;
            }));
        }
        start.countDown();
        for (Future<GrantResult> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(created.get()).isEqualTo(1);
        assertThat(grantRepository.findByAppIdAndUserIdOrderByGrantedAtAscIdAsc(appAId, userId)).hasSize(1);
        assertThat(membershipService.getStatus(appAId, userId).membershipExpiresAt())
                .isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    void concurrentDifferentSourcesBothApplied() throws Exception {
        Long userId = createUser(appAId);
        String refA = uniqueRef("conc-diff-a");
        String refB = uniqueRef("conc-diff-b");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<GrantResult> f1 = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return grant(appAId, userId, PlanCode.MONTHLY, refA);
        });
        Future<GrantResult> f2 = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return grant(appAId, userId, PlanCode.YEARLY, refB);
        });
        start.countDown();
        GrantResult r1 = f1.get(30, TimeUnit.SECONDS);
        GrantResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(r1.created()).isTrue();
        assertThat(r2.created()).isTrue();
        assertThat(grantRepository.findByAppIdAndUserIdOrderByGrantedAtAscIdAsc(appAId, userId)).hasSize(2);
        MembershipStatusView view = membershipService.getStatus(appAId, userId);
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2027-01-31T00:00:00Z"));
    }

    @Test
    void revokeOneGrant() {
        Long userId = createUser(appAId);
        String ref = uniqueRef("rev-1");
        grant(appAId, userId, PlanCode.MONTHLY, ref);
        MembershipStatusView view = membershipService.revoke(appAId, GrantSourceType.PAYMENT, ref, "refund");
        assertThat(view.status()).isEqualTo(MembershipStatus.REVOKED);
        assertThat(view.activeGrantCount()).isZero();
    }

    @Test
    void revokeIdempotent() {
        Long userId = createUser(appAId);
        String ref = uniqueRef("rev-idem");
        grant(appAId, userId, PlanCode.MONTHLY, ref);
        membershipService.revoke(appAId, GrantSourceType.PAYMENT, ref, "once");
        MembershipStatusView again = membershipService.revoke(appAId, GrantSourceType.PAYMENT, ref, "twice");
        assertThat(again.status()).isEqualTo(MembershipStatus.REVOKED);
    }

    @Test
    void revokeStackedRecalculates() {
        Long userId = createUser(appAId);
        String refA = uniqueRef("stack-rev-a");
        String refB = uniqueRef("stack-rev-b");
        grant(appAId, userId, PlanCode.MONTHLY, refA);
        ClockConfig.CLOCK.advance(Duration.ofDays(10));
        grant(appAId, userId, PlanCode.MONTHLY, refB);
        MembershipStatusView afterRevoke = membershipService.revoke(
                appAId, GrantSourceType.PAYMENT, refA, "partial-refund");
        assertThat(afterRevoke.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(afterRevoke.membershipStartedAt()).isEqualTo(Instant.parse("2026-01-11T00:00:00Z"));
        assertThat(afterRevoke.membershipExpiresAt()).isEqualTo(Instant.parse("2026-02-10T00:00:00Z"));
        assertThat(afterRevoke.activeGrantCount()).isEqualTo(1);
    }

    @Test
    void revokeLifetimeRestoresTimed() {
        Long userId = createUser(appAId);
        String refM = uniqueRef("life-rev-m");
        String refL = uniqueRef("life-rev-l");
        grant(appAId, userId, PlanCode.MONTHLY, refM);
        grant(appAId, userId, PlanCode.LIFETIME, refL);
        MembershipStatusView view = membershipService.revoke(
                appAId, GrantSourceType.PAYMENT, refL, "refund-life");
        assertThat(view.lifetime()).isFalse();
        assertThat(view.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    void expiredStatus() {
        Long userId = createUser(appAId);
        grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("expire-status"));
        ClockConfig.CLOCK.advance(Duration.ofDays(30));
        MembershipStatusView view = membershipService.getStatus(appAId, userId);
        assertThat(view.status()).isEqualTo(MembershipStatus.EXPIRED);
        assertThat(view.serverTime()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    void crossAppIsolation() {
        Long userA = createUser(appAId);
        Long userB = createUser(appBId);
        grant(appAId, userA, PlanCode.MONTHLY, uniqueRef("iso-a"));
        assertThat(membershipService.getStatus(appAId, userA).status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membershipService.getStatus(appBId, userB).status()).isEqualTo(MembershipStatus.NONE);
    }

    @Test
    void disabledPlanRejected() {
        Long userId = createUser(appAId);
        jdbcTemplate.update(
                "UPDATE membership_plans SET status = 'DISABLED' WHERE app_id = ? AND plan_code = 'MONTHLY'",
                appAId);
        try {
            assertThatThrownBy(() -> grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("disabled-plan")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.MEMBERSHIP_PLAN_DISABLED);
        } finally {
            jdbcTemplate.update(
                    "UPDATE membership_plans SET status = 'ACTIVE' WHERE app_id = ? AND plan_code = 'MONTHLY'",
                    appAId);
        }
    }

    @Test
    void revokeMissingGrantFails() {
        assertThatThrownBy(() ->
                        membershipService.revoke(appAId, GrantSourceType.PAYMENT, uniqueRef("missing-ref"), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MEMBERSHIP_GRANT_NOT_FOUND);
    }

    @Test
    void serverUtcClockUsed() {
        Long userId = createUser(appAId);
        ClockConfig.CLOCK.setInstant(Instant.parse("2026-06-15T12:00:00Z"));
        MembershipStatusView view = grant(appAId, userId, PlanCode.MONTHLY, uniqueRef("utc-clock")).status();
        assertThat(view.serverTime()).isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
        assertThat(view.membershipStartedAt()).isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
        assertThat(view.membershipExpiresAt()).isEqualTo(Instant.parse("2026-07-15T12:00:00Z"));
    }

    private static String uniqueRef(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private GrantResult grant(Long appId, Long userId, PlanCode planCode, String sourceRef) {
        return membershipService.grant(new GrantCommand(
                appId, userId, planCode, GrantSourceType.PAYMENT, sourceRef, "order-" + sourceRef));
    }

    private Long createUser(Long appId) {
        return userRepository.saveAndFlush(UserEntity.create(appId, UserStatus.ACTIVE)).getId();
    }

    private void ensureSecondApp() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apps WHERE app_code = ?", Integer.class, APP_B);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                    APP_B, "Speaker Cleaner", "ANDROID", "com.aurora.speakercleaner", "ACTIVE");
        }
    }

    private void ensureAppBPlans() {
        Long appId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_B);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM membership_plans WHERE app_id = ?", Integer.class, appId);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status) VALUES (?, 'MONTHLY', '月卡', 590, 30, 0, 'ACTIVE')",
                    appId);
            jdbcTemplate.update(
                    "INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status) VALUES (?, 'YEARLY', '年卡', 1990, 365, 0, 'ACTIVE')",
                    appId);
            jdbcTemplate.update(
                    "INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status) VALUES (?, 'LIFETIME', '永久', 2990, NULL, 1, 'ACTIVE')",
                    appId);
        }
    }
}
