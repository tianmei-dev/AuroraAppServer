package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.AuroraAppServerApplication;
import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.repository.MembershipPlanRepository;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.membership.service.MembershipService.GrantCommand;
import com.aurora.appserver.membership.service.MembershipService.MembershipStatusView;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.provider.yusen.YusenSigner;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import com.aurora.appserver.payment.service.MembershipFulfillmentPort;
import com.aurora.appserver.payment.service.MembershipServiceFulfillmentAdapter;
import com.aurora.appserver.payment.service.OrderNoGenerator;
import com.aurora.appserver.payment.service.PaymentConfirmationService;
import com.aurora.appserver.payment.service.PaymentJobService;
import com.aurora.appserver.payment.service.PaymentNotificationService;
import com.aurora.appserver.payment.support.FakePaymentProvider;
import com.aurora.appserver.payment.support.StubYusenHttpTransport;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.support.TestConfigurationExcludeFilter;
import com.aurora.appserver.trial.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PENDING_PAYMENT -> PAID -> FULFILLED end to end on aurora_app_test.
 * Payment outcomes are simulated only through FakePaymentProvider and StubYusenHttpTransport.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestWechatAuthClient.Config.class, PaymentConfirmationIT.TestConfig.class})
class PaymentConfirmationIT {

    private static final String APP_A = "calculator_vault";
    private static final String APP_B = "speaker_cleaner";
    private static final Instant T0 = Instant.parse("2026-11-06T13:00:00Z");
    private static final String YUSEN_KEY = "fictional-merchant-key-0001";
    private static final String YUSEN_PID = "1001";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @TestConfiguration
    static class TestConfig {
        static final MutableClock CLOCK = new MutableClock(T0);

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        FaultInjectingFulfillmentPort faultInjectingFulfillmentPort(MembershipServiceFulfillmentAdapter real) {
            return new FaultInjectingFulfillmentPort(real);
        }
    }

    /** Test-only wrapper that can make the next N grants fail. */
    static class FaultInjectingFulfillmentPort implements MembershipFulfillmentPort {
        private final MembershipFulfillmentPort delegate;
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        FaultInjectingFulfillmentPort(MembershipFulfillmentPort delegate) {
            this.delegate = delegate;
        }

        void failNext(int times) {
            failuresRemaining.set(times);
        }

        void reset() {
            failuresRemaining.set(0);
        }

        @Override
        public Long grantForPaidOrder(Long appId, String orderNo) {
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("injected fulfillment failure");
            }
            return delegate.grantForPaidOrder(appId, orderNo);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestWechatAuthClient wechatAuthClient;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PaymentConfirmationService confirmationService;
    @Autowired private PaymentNotificationService notificationService;
    @Autowired private PaymentJobService jobService;
    @Autowired private MembershipService membershipService;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private MembershipPlanRepository planRepository;
    @Autowired private OrderNoGenerator orderNoGenerator;
    @Autowired private FaultInjectingFulfillmentPort faultPort;

    private Long appAId;
    private Long appBId;
    private long baselineOrderId;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
        TestConfig.CLOCK.setInstant(T0);
        FakePaymentProvider.reset();
        StubYusenHttpTransport.reset();
        faultPort.reset();
        ensureApp(APP_B, "Speaker Cleaner", "com.aurora.speakercleaner");
        appAId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_A);
        appBId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_B);
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM payment_orders", Long.class);
        baselineOrderId = max == null ? 0 : max;
    }

    // ================================================================== normal success

    @Test
    void yusenNotifyConfirmsByQueryAndGrantsEachPlanFromSnapshot() throws Exception {
        Object[][] plans = {
                {PlanCode.MONTHLY, "5.90", 30, false},
                {PlanCode.YEARLY, "19.90", 365, false},
                {PlanCode.LIFETIME, "29.90", null, true},
        };
        for (Object[] p : plans) {
            Login login = login(APP_A);
            PaymentOrderEntity order = createYusenOrder(login.userId(), (PlanCode) p[0]);
            StubYusenHttpTransport.paid(order.getOrderNo(), (String) p[1], YUSEN_PID);

            String body = sendYusenNotify(yusenNotify(order.getOrderNo(), (String) p[1], "TRADE_SUCCESS"));
            assertThat(body).isEqualTo("success");
            awaitStatus(order.getOrderNo(), "FULFILLED");

            Map<String, Object> grant = singlePaymentGrant(order.getOrderNo());
            assertThat(grant.get("order_id")).isEqualTo(order.getOrderNo());
            assertThat(grant.get("plan_code")).isEqualTo(((PlanCode) p[0]).name());
            assertThat(grant.get("duration_days")).isEqualTo(p[2]);
            assertThat(flag(grant.get("is_lifetime"))).isEqualTo((Boolean) p[3] ? 1 : 0);

            MembershipStatusView view = membershipService.getStatus(appAId, login.userId());
            assertThat(view.status()).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(view.lifetime()).isEqualTo(p[3]);
            if (p[2] != null) {
                assertThat(view.membershipExpiresAt()).isEqualTo(T0.plus(Duration.ofDays((Integer) p[2])));
            }

            assertThat(notificationResults(order.getOrderNo())).containsExactly("APPLIED");
            assertThat(StubYusenHttpTransport.lastQuery(order.getOrderNo()))
                    .containsEntry("act", "order").containsEntry("key", YUSEN_KEY);
            List<Map<String, Object>> calls = jdbcTemplate.queryForList(
                    "SELECT * FROM payment_provider_calls WHERE order_no = ?", order.getOrderNo());
            assertThat(calls).isNotEmpty();
            for (Map<String, Object> call : calls) {
                assertThat(String.valueOf(call.get("provider_msg"))).doesNotContain(YUSEN_KEY);
            }
            Map<String, Object> row = orderRow(order.getOrderNo());
            assertThat(row.get("provider_trade_no")).isEqualTo("Y" + order.getOrderNo());
            assertThat(row.get("upstream_trade_no")).isEqualTo("WX" + order.getOrderNo());
            assertThat(((Number) row.get("membership_grant_id")).longValue())
                    .isEqualTo(((Number) grant.get("id")).longValue());
        }
    }

    // ================================================================== idempotency & concurrency

    @Test
    void duplicateAndConcurrentNotificationsGrantOnce() throws Exception {
        Login login = login(APP_A);
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.YEARLY);
        StubYusenHttpTransport.paid(order.getOrderNo(), "19.90", YUSEN_PID);
        Map<String, String> params = yusenNotify(order.getOrderNo(), "19.90", "TRADE_SUCCESS");

        for (int i = 0; i < 3; i++) {
            assertThat(sendYusenNotify(params)).isEqualTo("success");
        }
        List<String> bodies = runConcurrently(6, i -> sendYusenNotify(params));
        assertThat(bodies).containsOnly("success");

        awaitStatus(order.getOrderNo(), "FULFILLED");
        assertThat(paymentGrantCount(order.getOrderNo())).isEqualTo(1);
        assertThat(eventCount(order.getId(), "PAID")).isEqualTo(1);
        assertThat(eventCount(order.getId(), "FULFILLED")).isEqualTo(1);
        assertThat(notificationResults(order.getOrderNo())).containsExactly("APPLIED");
    }

    @Test
    void notifyQueryAndJobsConfirmConcurrentlyButGrantOnce() throws Exception {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "MONTHLY");
        FakePaymentProvider.paid(order.orderNo(), 590);
        Map<String, String> notify = FakePaymentProvider.signedNotification(order.orderNo(), "5.90", "TRADE_SUCCESS");

        runConcurrently(10, i -> {
            if (i < 4) {
                return confirmationService.confirmByQuery(order.id(), PaymentOrderTrigger.QUERY, false).name();
            }
            if (i < 8) {
                return notificationService.handle(PaymentProviderCode.FAKE, notify, "127.0.0.1");
            }
            return jobService.runCycle(baselineOrderId).toString();
        });

        awaitStatus(order.orderNo(), "FULFILLED");
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
        assertThat(eventCount(order.id(), "PAID")).isEqualTo(1);
        assertThat(eventCount(order.id(), "FULFILLED")).isEqualTo(1);
    }

    @Test
    void concurrentJobInstancesGrantOnce() throws Exception {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "LIFETIME");
        FakePaymentProvider.paid(order.orderNo(), 2990);

        runConcurrently(4, i -> jobService.runCycle(baselineOrderId).toString());

        assertThat(orderStatus(order.orderNo())).isEqualTo("FULFILLED");
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
        assertThat(eventCount(order.id(), "PAID")).isEqualTo(1);
        assertThat(membershipService.getStatus(appAId, login.userId()).lifetime()).isTrue();
    }

    // ================================================================== no grant without trusted payment

    @Test
    void unpaidOrderIsNeverGrantedEvenWithSuccessNotification() {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "MONTHLY");

        assertThat(confirmationService.confirmByQuery(order.id(), PaymentOrderTrigger.QUERY, false))
                .isEqualTo(PaymentConfirmationService.Outcome.NOT_PAID);
        assertThatThrownBy(() -> membershipService.grantForPaidOrder(appAId, order.orderNo()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PAYMENT_ORDER_NOT_CONFIRMED);

        String ack = notificationService.handle(PaymentProviderCode.FAKE,
                FakePaymentProvider.signedNotification(order.orderNo(), "5.90", "TRADE_SUCCESS"), "127.0.0.1");
        assertThat(ack).isEqualTo("success");
        assertThat(orderStatus(order.orderNo())).isEqualTo("PENDING_PAYMENT");
        assertThat(notificationResults(order.orderNo())).containsExactly("PENDING_CONFIRM");
        assertThat(paymentGrantCount(order.orderNo())).isZero();
        assertThat(membershipService.getStatus(appAId, login.userId()).status()).isEqualTo(MembershipStatus.NONE);
    }

    @Test
    void invalidSignatureOrMerchantIsRejectedWithoutQuery() throws Exception {
        Login login = login(APP_A);
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.MONTHLY);
        StubYusenHttpTransport.paid(order.getOrderNo(), "5.90", YUSEN_PID);

        Map<String, String> tampered = yusenNotify(order.getOrderNo(), "5.90", "TRADE_SUCCESS");
        tampered.put("money", "0.01");
        assertThat(mockMvc.perform(notifyRequest(tampered)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer junk-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("fail");

        Map<String, String> wrongPid = new LinkedHashMap<>(yusenNotify(order.getOrderNo(), "5.90", "TRADE_SUCCESS"));
        wrongPid.put("pid", "9999");
        wrongPid.remove("sign");
        wrongPid.put("sign", YusenSigner.sign(wrongPid, YUSEN_KEY));
        assertThat(sendYusenNotify(wrongPid)).isEqualTo("fail");

        Map<String, String> unsigned = yusenNotify(order.getOrderNo(), "5.90", "TRADE_SUCCESS");
        unsigned.remove("sign");
        assertThat(sendYusenNotify(unsigned)).isEqualTo("fail");

        assertThat(notificationResults(order.getOrderNo()))
                .containsExactlyInAnyOrder("REJECTED_SIGN", "REJECTED_PID", "REJECTED_SIGN");
        assertThat(StubYusenHttpTransport.calls(order.getOrderNo())).isZero();
        assertThat(orderStatus(order.getOrderNo())).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentGrantCount(order.getOrderNo())).isZero();
    }

    @Test
    void notificationAmountMismatchUnknownOrderAndNonSuccessStatusAreNotGranted() throws Exception {
        Login login = login(APP_A);
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.YEARLY);
        StubYusenHttpTransport.paid(order.getOrderNo(), "19.90", YUSEN_PID);

        assertThat(sendYusenNotify(yusenNotify(order.getOrderNo(), "0.01", "TRADE_SUCCESS"))).isEqualTo("success");
        assertThat(sendYusenNotify(yusenNotify(order.getOrderNo(), "19.90", "WAIT_BUYER_PAY"))).isEqualTo("success");
        assertThat(notificationResults(order.getOrderNo())).containsExactly("AMOUNT_MISMATCH", "IGNORED_STATUS");
        assertThat(orderRow(order.getOrderNo()).get("anomaly_code")).isEqualTo("NOTIFY_AMOUNT_MISMATCH");
        assertThat(StubYusenHttpTransport.calls(order.getOrderNo())).isZero();
        assertThat(orderStatus(order.getOrderNo())).isEqualTo("PENDING_PAYMENT");

        String unknownOrderNo = "CV20991231235959ZZZZZZZZZZZZ";
        assertThat(sendYusenNotify(yusenNotify(unknownOrderNo, "5.90", "TRADE_SUCCESS"))).isEqualTo("success");
        assertThat(notificationResults(unknownOrderNo)).containsExactly("ORDER_NOT_FOUND");
        assertThat(paymentGrantCount(order.getOrderNo())).isZero();
    }

    @Test
    void queryMismatchesInAmountMerchantOrOrderNumberBlockGrant() throws Exception {
        Login login = login(APP_A);
        Order amount = createFakeOrder(login, "MONTHLY");
        FakePaymentProvider.paidWith(amount.orderNo(), 1, FakePaymentProvider.FAKE_PID, null);
        Order merchant = createFakeOrder(login, "YEARLY");
        FakePaymentProvider.paidWith(merchant.orderNo(), 1990, "other-merchant", null);
        Order orderNo = createFakeOrder(login, "LIFETIME");
        FakePaymentProvider.paidWith(orderNo.orderNo(), 2990, FakePaymentProvider.FAKE_PID, "CV-SOMEONE-ELSE");

        assertMismatch(amount, "QUERY_AMOUNT_MISMATCH");
        assertMismatch(merchant, "QUERY_MERCHANT_MISMATCH");
        assertMismatch(orderNo, "QUERY_ORDER_MISMATCH");

        // Same rule through the real Yusen adapter: signed notify ok, query reports another amount.
        PaymentOrderEntity yusen = createYusenOrder(login.userId(), PlanCode.MONTHLY);
        StubYusenHttpTransport.paid(yusen.getOrderNo(), "0.01", YUSEN_PID);
        assertThat(sendYusenNotify(yusenNotify(yusen.getOrderNo(), "5.90", "TRADE_SUCCESS"))).isEqualTo("success");
        assertThat(orderRow(yusen.getOrderNo()).get("anomaly_code")).isEqualTo("QUERY_AMOUNT_MISMATCH");
        assertThat(orderStatus(yusen.getOrderNo())).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentGrantCount(yusen.getOrderNo())).isZero();
        assertThat(membershipService.getStatus(appAId, login.userId()).status()).isEqualTo(MembershipStatus.NONE);
    }

    // ================================================================== snapshot

    @Test
    void snapshotGrantIgnoresLaterPlanRepriceDurationChangeAndDisable() {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "MONTHLY");
        jdbcTemplate.update("UPDATE membership_plans SET price_fen = 9990, duration_days = 7, status = 'DISABLED' "
                + "WHERE app_id = ? AND plan_code = 'MONTHLY'", appAId);
        try {
            FakePaymentProvider.paid(order.orderNo(), 590);
            assertThat(confirmationService.confirmByQuery(order.id(), PaymentOrderTrigger.QUERY, false))
                    .isEqualTo(PaymentConfirmationService.Outcome.PAID_CONFIRMED);

            assertThat(orderStatus(order.orderNo())).isEqualTo("FULFILLED");
            Map<String, Object> grant = singlePaymentGrant(order.orderNo());
            assertThat(grant.get("duration_days")).isEqualTo(30);
            assertThat(membershipService.getStatus(appAId, login.userId()).membershipExpiresAt())
                    .isEqualTo(T0.plus(Duration.ofDays(30)));
        } finally {
            jdbcTemplate.update("UPDATE membership_plans SET price_fen = 590, duration_days = 30, status = 'ACTIVE' "
                    + "WHERE app_id = ? AND plan_code = 'MONTHLY'", appAId);
        }
        assertThat(planRepository.findByAppIdAndPlanCode(appAId, PlanCode.MONTHLY).orElseThrow().getPriceFen())
                .isEqualTo(590);
    }

    // ================================================================== compensation

    @Test
    void fulfillmentFailureIsRetriedWithBackoffUntilGranted() {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "YEARLY");
        FakePaymentProvider.paid(order.orderNo(), 1990);
        faultPort.failNext(2);

        assertThat(confirmationService.confirmByQuery(order.id(), PaymentOrderTrigger.QUERY, false))
                .isEqualTo(PaymentConfirmationService.Outcome.PAID_CONFIRMED);
        Map<String, Object> afterFirst = orderRow(order.orderNo());
        assertThat(afterFirst.get("status")).isEqualTo("PAID");
        assertThat(afterFirst.get("fulfill_attempts")).isEqualTo(1);
        assertThat(String.valueOf(afterFirst.get("last_fulfill_error"))).contains("IllegalStateException");
        assertThat(paymentGrantCount(order.orderNo())).isZero();

        jobService.runCycle(baselineOrderId);
        assertThat(orderRow(order.orderNo()).get("fulfill_attempts")).isEqualTo(1);

        TestConfig.CLOCK.advance(Duration.ofSeconds(31));
        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("PAID");
        assertThat(orderRow(order.orderNo()).get("fulfill_attempts")).isEqualTo(2);

        TestConfig.CLOCK.advance(Duration.ofSeconds(61));
        jobService.runCycle(baselineOrderId);
        Map<String, Object> done = orderRow(order.orderNo());
        assertThat(done.get("status")).isEqualTo("FULFILLED");
        assertThat(done.get("fulfill_attempts")).isEqualTo(3);
        assertThat(done.get("last_fulfill_error")).isNull();
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
    }

    @Test
    void freshApplicationContextResumesPendingWorkAfterRestart() throws Exception {
        Login login = login(APP_A);
        Order stuckPaid = createFakeOrder(login, "MONTHLY");
        FakePaymentProvider.paid(stuckPaid.orderNo(), 590);
        faultPort.failNext(1);
        confirmationService.confirmByQuery(stuckPaid.id(), PaymentOrderTrigger.QUERY, false);
        assertThat(orderStatus(stuckPaid.orderNo())).isEqualTo("PAID");

        Login other = login(APP_A);
        PaymentOrderEntity paidWhileDown = createYusenOrder(other.userId(), PlanCode.YEARLY);
        StubYusenHttpTransport.paid(paidWhileDown.getOrderNo(), "19.90", YUSEN_PID);

        // "Restart": a brand-new application context with no shared in-memory payment state,
        // system clock, jobs driven once exactly as the scheduler's first run would.
        SpringApplication restarted = new SpringApplication(AuroraAppServerApplication.class);
        restarted.setAdditionalProfiles("test");
        Map<String, Object> props = new HashMap<>(ExternalMySqlSupport.datasourceProperties());
        props.put("server.port", "0");
        props.put("spring.main.banner-mode", "off");
        props.put("aurora.payment.jobs.enabled", "false");
        // Command-line args outrank application.yml, so the isolated test database always wins.
        String[] args = props.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        restarted.addInitializers(ctx -> ctx.getBeanFactory()
                .registerSingleton("restartTestConfigExclude", new TestConfigurationExcludeFilter()));
        try (ConfigurableApplicationContext ctx = restarted.run(args)) {
            String database = ctx.getBean(JdbcTemplate.class).queryForObject("SELECT DATABASE()", String.class);
            assertThat(database).isEqualTo("aurora_app_test");
            ctx.getBean(PaymentJobService.class).runCycle();
        }

        assertThat(orderStatus(stuckPaid.orderNo())).isEqualTo("FULFILLED");
        assertThat(orderStatus(paidWhileDown.getOrderNo())).isEqualTo("FULFILLED");
        assertThat(paymentGrantCount(stuckPaid.orderNo())).isEqualTo(1);
        assertThat(paymentGrantCount(paidWhileDown.getOrderNo())).isEqualTo(1);
    }

    // ================================================================== expiry & late payment

    @Test
    void unpaidOrderExpiresThenLatePaymentWithinPatrolIsGranted() {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "MONTHLY");

        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("PENDING_PAYMENT");

        TestConfig.CLOCK.advance(Duration.ofMinutes(31));
        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("EXPIRED");
        assertThat(orderRow(order.orderNo()).get("anomaly_code")).isNull();

        FakePaymentProvider.paid(order.orderNo(), 590);
        TestConfig.CLOCK.advance(Duration.ofMinutes(15));
        jobService.runCycle(baselineOrderId);

        Map<String, Object> row = orderRow(order.orderNo());
        assertThat(row.get("status")).isEqualTo("FULFILLED");
        assertThat(flag(row.get("late_paid"))).isEqualTo(1);
        assertThat(row.get("anomaly_code")).isEqualTo("LATE_PAYMENT");
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
        assertThat(events(order.id())).containsSubsequence(
                "null>PENDING_PAYMENT:CREATE", "PENDING_PAYMENT>EXPIRED:EXPIRE_JOB",
                "EXPIRED>PAID:QUERY", "PAID>FULFILLED:FULFILL");
    }

    @Test
    void trustedLateNotificationBeyondPatrolWindowIsStillHonoured() throws Exception {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "YEARLY");
        TestConfig.CLOCK.advance(Duration.ofMinutes(31));
        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("EXPIRED");

        FakePaymentProvider.paid(order.orderNo(), 1990);
        TestConfig.CLOCK.advance(Duration.ofHours(25));
        int queriesBefore = FakePaymentProvider.queryCount(order.orderNo());
        jobService.runCycle(baselineOrderId);
        assertThat(FakePaymentProvider.queryCount(order.orderNo())).isEqualTo(queriesBefore);
        assertThat(orderStatus(order.orderNo())).isEqualTo("EXPIRED");

        assertThat(notificationService.handle(PaymentProviderCode.FAKE,
                FakePaymentProvider.signedNotification(order.orderNo(), "19.90", "TRADE_SUCCESS"), "127.0.0.1"))
                .isEqualTo("success");
        awaitStatus(order.orderNo(), "FULFILLED");
        Map<String, Object> row = orderRow(order.orderNo());
        assertThat(row.get("status")).isEqualTo("FULFILLED");
        assertThat(flag(row.get("late_paid"))).isEqualTo(1);
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
    }

    @Test
    void inconclusiveQueriesNeverFailAnOrderAndExpireOnlyAfterGrace() {
        Login login = login(APP_A);
        Order order = createFakeOrder(login, "MONTHLY");
        FakePaymentProvider.mode(order.orderNo(), FakePaymentProvider.Mode.TIMEOUT);

        jobService.runCycle(baselineOrderId);
        TestConfig.CLOCK.advance(Duration.ofMinutes(31));
        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("PENDING_PAYMENT");

        FakePaymentProvider.mode(order.orderNo(), FakePaymentProvider.Mode.THROW);
        TestConfig.CLOCK.advance(Duration.ofMinutes(10));
        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("PENDING_PAYMENT");

        FakePaymentProvider.mode(order.orderNo(), FakePaymentProvider.Mode.ERROR);
        TestConfig.CLOCK.advance(Duration.ofMinutes(21));
        jobService.runCycle(baselineOrderId);
        Map<String, Object> expired = orderRow(order.orderNo());
        assertThat(expired.get("status")).isEqualTo("EXPIRED");
        assertThat(expired.get("anomaly_code")).isEqualTo("EXPIRED_INCONCLUSIVE");
        assertThat(paymentGrantCount(order.orderNo())).isZero();

        FakePaymentProvider.paid(order.orderNo(), 590);
        TestConfig.CLOCK.advance(Duration.ofMinutes(11));
        jobService.runCycle(baselineOrderId);
        assertThat(orderStatus(order.orderNo())).isEqualTo("FULFILLED");
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
    }

    @Test
    void slowConfirmationQueryDoesNotBlockNotifyResponse() throws Exception {
        Login login = login(APP_A);
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.MONTHLY);
        StubYusenHttpTransport.respondSlowly(order.getOrderNo(),
                StubYusenHttpTransport.paidJson(order.getOrderNo(), "5.90", YUSEN_PID), 4000);

        long started = System.nanoTime();
        String body = sendYusenNotify(yusenNotify(order.getOrderNo(), "5.90", "TRADE_SUCCESS"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(body).isEqualTo("success");
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(orderStatus(order.getOrderNo())).isEqualTo("PENDING_PAYMENT");
        assertThat(notificationResults(order.getOrderNo())).containsExactly("PENDING_CONFIRM");

        awaitStatus(order.getOrderNo(), "FULFILLED");
        assertThat(notificationResults(order.getOrderNo())).containsExactly("APPLIED");
        assertThat(paymentGrantCount(order.getOrderNo())).isEqualTo(1);
    }

    // ================================================================== isolation & entry points

    @Test
    void paidOrderGrantsOnlyItsOwnUserAndApp() {
        Login alice = login(APP_A);
        Login bob = login(APP_A);
        Login appBUser = login(APP_B);
        Order order = createFakeOrder(alice, "YEARLY");
        FakePaymentProvider.paid(order.orderNo(), 1990);
        confirmationService.confirmByQuery(order.id(), PaymentOrderTrigger.QUERY, false);
        assertThat(orderStatus(order.orderNo())).isEqualTo("FULFILLED");

        assertThat(membershipService.getStatus(appAId, alice.userId()).status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membershipService.getStatus(appAId, bob.userId()).status()).isEqualTo(MembershipStatus.NONE);
        assertThat(membershipService.getStatus(appBId, appBUser.userId()).status()).isEqualTo(MembershipStatus.NONE);

        assertThatThrownBy(() -> membershipService.grantForPaidOrder(appBId, order.orderNo()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PAYMENT_ORDER_NOT_CONFIRMED);
        assertThat(membershipService.grantForPaidOrder(appAId, order.orderNo()).created()).isFalse();
        assertThat(paymentGrantCount(order.orderNo())).isEqualTo(1);
    }

    @Test
    void genericGrantCannotCreatePaymentMembership() {
        Login login = login(APP_A);
        assertThatThrownBy(() -> membershipService.grant(new GrantCommand(
                appAId, login.userId(), PlanCode.LIFETIME, GrantSourceType.PAYMENT, "CV-FORGED-1", "CV-FORGED-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MEMBERSHIP_GRANT_SOURCE_INVALID);
        assertThat(membershipService.getStatus(appAId, login.userId()).status()).isEqualTo(MembershipStatus.NONE);
    }

    @Test
    void returnPageNeverConfirmsOrEchoesParameters() throws Exception {
        Login login = login(APP_A);
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.LIFETIME);
        StubYusenHttpTransport.paid(order.getOrderNo(), "29.90", YUSEN_PID);
        Map<String, String> params = yusenNotify(order.getOrderNo(), "29.90", "TRADE_SUCCESS");
        params.put("name", "<script>alert(1)</script>");

        MockHttpServletRequestBuilder request = get("/api/v1/payment/return/yusen");
        params.forEach(request::param);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/html");
        String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("请返回 App 查看支付结果").doesNotContain("<script>alert").doesNotContain(order.getOrderNo());
        assertThat(StubYusenHttpTransport.calls(order.getOrderNo())).isZero();
        assertThat(notificationResults(order.getOrderNo())).isEmpty();
        assertThat(orderStatus(order.getOrderNo())).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentGrantCount(order.getOrderNo())).isZero();
    }

    // ================================================================== helpers

    private record Login(long userId, String token) {
    }

    private record Order(long id, String orderNo) {
    }

    @FunctionalInterface
    private interface IndexedCall {
        String call(int index) throws Exception;
    }

    private void assertMismatch(Order order, String anomaly) {
        assertThat(confirmationService.confirmByQuery(order.id(), PaymentOrderTrigger.QUERY, false))
                .isEqualTo(PaymentConfirmationService.Outcome.MISMATCH);
        Map<String, Object> row = orderRow(order.orderNo());
        assertThat(row.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(row.get("anomaly_code")).isEqualTo(anomaly);
        assertThat(paymentGrantCount(order.orderNo())).isZero();
    }

    private List<String> runConcurrently(int threads, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<String> task = () -> {
                    start.await();
                    return call.call(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Login login(String appCode) {
        try {
            String suffix = "pay-confirm-" + UUID.randomUUID();
            String code = "code-" + suffix;
            wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok("openid-" + suffix));
            MvcResult result = mockMvc.perform(post("/api/v1/auth/wechat/login")
                            .header("X-App-Code", appCode)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
            return new Login(data.get("userId").asLong(), data.get("accessToken").asText());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Order createFakeOrder(Login login, String planCode) {
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/payment/orders")
                            .header("X-App-Code", APP_A)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"planCode\":\"" + planCode + "\",\"clientRequestId\":\""
                                    + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String orderNo = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("data").get("orderNo").asText();
            long id = ((Number) orderRow(orderNo).get("id")).longValue();
            return new Order(id, orderNo);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Yusen-provider order (the active test provider is FAKE, so it is persisted directly). */
    private PaymentOrderEntity createYusenOrder(long userId, PlanCode planCode) {
        var plan = planRepository.findByAppIdAndPlanCode(appAId, planCode).orElseThrow();
        Instant now = TestConfig.CLOCK.instant();
        return orderRepository.saveAndFlush(PaymentOrderEntity.createPending(
                appAId, userId, orderNoGenerator.next(now), "it-" + UUID.randomUUID(), plan,
                PaymentProviderCode.YUSEN, PaymentEnvironment.TEST, YUSEN_PID, now.plus(Duration.ofMinutes(30))));
    }

    private static Map<String, String> yusenNotify(String orderNo, String money, String tradeStatus) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", YUSEN_PID);
        params.put("trade_no", "Y" + orderNo);
        params.put("out_trade_no", orderNo);
        params.put("type", "wxpay");
        params.put("name", "会员");
        params.put("money", money);
        params.put("trade_status", tradeStatus);
        params.put("sign", YusenSigner.sign(params, YUSEN_KEY));
        params.put("sign_type", "MD5");
        return params;
    }

    private MockHttpServletRequestBuilder notifyRequest(Map<String, String> params) {
        MockHttpServletRequestBuilder request = get("/api/v1/payment/notify/yusen");
        params.forEach(request::param);
        return request;
    }

    private String sendYusenNotify(Map<String, String> params) throws Exception {
        return mockMvc.perform(notifyRequest(params))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void awaitStatus(String orderNo, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(orderStatus(orderNo))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(orderStatus(orderNo)).isEqualTo(expected);
    }

    private String orderStatus(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_orders WHERE order_no = ?", String.class, orderNo);
    }

    private Map<String, Object> orderRow(String orderNo) {
        return jdbcTemplate.queryForMap("SELECT * FROM payment_orders WHERE order_no = ?", orderNo);
    }

    private long paymentGrantCount(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM membership_grants WHERE source_type = 'PAYMENT' AND source_ref = ?",
                Long.class, orderNo);
    }

    private Map<String, Object> singlePaymentGrant(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM membership_grants WHERE source_type = 'PAYMENT' AND source_ref = ?", orderNo);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long eventCount(long orderId, String toStatus) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_order_events WHERE order_id = ? AND to_status = ? "
                        + "AND (from_status IS NULL OR from_status <> to_status)",
                Long.class, orderId, toStatus);
    }

    private List<String> events(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT CONCAT(COALESCE(from_status, 'null'), '>', to_status, ':', trigger_source) "
                        + "FROM payment_order_events WHERE order_id = ? ORDER BY id",
                String.class, orderId);
    }

    private List<String> notificationResults(String orderNo) {
        return jdbcTemplate.queryForList(
                "SELECT process_result FROM payment_notifications WHERE order_no = ? ORDER BY id",
                String.class, orderNo);
    }

    private static int flag(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) value).intValue();
    }

    private void ensureApp(String appCode, String name, String packageName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apps WHERE app_code = ?", Integer.class, appCode);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                    appCode, name, "ANDROID", packageName, "ACTIVE");
        }
    }
}
