package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.repository.MembershipPlanRepository;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.provider.yusen.YusenSigner;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import com.aurora.appserver.payment.service.OrderNoGenerator;
import com.aurora.appserver.payment.service.PaymentConfirmationService;
import com.aurora.appserver.payment.service.PaymentNotificationService;
import com.aurora.appserver.payment.support.FakePaymentProvider;
import com.aurora.appserver.payment.support.StubYusenHttpTransport;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.trial.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * B3-04-A2 high-risk gaps on aurora_app_test only (PAY-P0-015/016, PAY-P1-010/011).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestWechatAuthClient.Config.class, PaymentHighRiskGapIT.TestConfig.class})
class PaymentHighRiskGapIT {

    private static final String APP = "calculator_vault";
    private static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");
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
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestWechatAuthClient wechatAuthClient;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PaymentConfirmationService confirmationService;
    @Autowired private PaymentNotificationService notificationService;
    @Autowired private MembershipService membershipService;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private MembershipPlanRepository planRepository;
    @Autowired private OrderNoGenerator orderNoGenerator;

    private Long appId;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
        TestConfig.CLOCK.setInstant(T0);
        FakePaymentProvider.reset();
        StubYusenHttpTransport.reset();
        appId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP);
    }

    /** PAY-P0-015 */
    @Test
    void p0015_yusenNotifyWithNetworkDisabledRecordsButDoesNotConfirmOrGrant() throws Exception {
        Login login = login("openid-p0015");
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.YEARLY);
        StubYusenHttpTransport.simulateNetworkDisabled();

        Map<String, String> params = yusenNotify(order.getOrderNo(), "19.90", "TRADE_SUCCESS");
        assertThat(sendYusenNotify(params)).isEqualTo("success");
        Thread.sleep(1500);

        List<Map<String, Object>> notifications = jdbcTemplate.queryForList(
                "SELECT process_result, sign_valid FROM payment_notifications WHERE order_no = ? ORDER BY id",
                order.getOrderNo());
        assertThat(notifications).isNotEmpty();
        assertThat(notifications.get(0).get("process_result")).isEqualTo("PENDING_CONFIRM");
        assertThat(flag(notifications.get(0).get("sign_valid"))).isEqualTo(1);

        Map<String, Object> row = orderRow(order.getOrderNo());
        assertThat(row.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(row.get("paid_at")).isNull();
        assertThat(row.get("membership_grant_id")).isNull();
        assertThat(paymentGrantCount(order.getOrderNo())).isZero();
        assertThat(membershipService.getStatus(appId, login.userId()).status()).isEqualTo(MembershipStatus.NONE);
        assertThat(StubYusenHttpTransport.calls(order.getOrderNo())).isGreaterThanOrEqualTo(1);
    }

    /** PAY-P0-016 */
    @Test
    void p0016_duplicateProviderTradeNoBlocksSecondFulfillment() {
        Login alice = login("openid-p0016-a");
        Login bob = login("openid-p0016-b");
        PaymentOrderEntity first = createYusenOrder(alice.userId(), PlanCode.YEARLY);
        PaymentOrderEntity second = createYusenOrder(bob.userId(), PlanCode.YEARLY);
        String sharedTradeNo = "SHARED-TRADE-P0016-" + UUID.randomUUID();

        StubYusenHttpTransport.paidWithTradeNo(first.getOrderNo(), "19.90", YUSEN_PID, sharedTradeNo);
        StubYusenHttpTransport.paidWithTradeNo(second.getOrderNo(), "19.90", YUSEN_PID, sharedTradeNo);

        assertThat(confirmationService.confirmByQuery(first.getId(), PaymentOrderTrigger.QUERY, false))
                .isEqualTo(PaymentConfirmationService.Outcome.PAID_CONFIRMED);
        awaitStatus(first.getOrderNo(), "FULFILLED");
        assertThat(paymentGrantCount(first.getOrderNo())).isEqualTo(1);
        assertThat(membershipService.getStatus(appId, alice.userId()).status()).isEqualTo(MembershipStatus.ACTIVE);

        assertThat(confirmationService.confirmByQuery(second.getId(), PaymentOrderTrigger.QUERY, false))
                .isEqualTo(PaymentConfirmationService.Outcome.MISMATCH);

        Map<String, Object> secondRow = orderRow(second.getOrderNo());
        assertThat(secondRow.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(secondRow.get("provider_trade_no")).isNull();
        assertThat(secondRow.get("anomaly_code")).isEqualTo("QUERY_ORDER_MISMATCH");
        assertThat(secondRow.get("membership_grant_id")).isNull();
        assertThat(paymentGrantCount(second.getOrderNo())).isZero();
        assertThat(membershipService.getStatus(appId, bob.userId()).status()).isEqualTo(MembershipStatus.NONE);

        assertThat(orderStatus(first.getOrderNo())).isEqualTo("FULFILLED");
        assertThat(paymentGrantCount(first.getOrderNo())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE provider_trade_no = ?", Long.class, sharedTradeNo))
                .isEqualTo(1);
    }

    /** PAY-P1-011 */
    @Test
    void p1011_notificationPersistenceOmitsSignAndMerchantKey() throws Exception {
        Login login = login("openid-p1011");
        PaymentOrderEntity order = createYusenOrder(login.userId(), PlanCode.MONTHLY);
        StubYusenHttpTransport.simulateNetworkDisabled();

        Map<String, String> params = yusenNotify(order.getOrderNo(), "5.90", "TRADE_SUCCESS");
        String signValue = params.get("sign");
        assertThat(signValue).isNotBlank();
        assertThat(sendYusenNotify(params)).isEqualTo("success");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT params_sanitized, sign_valid FROM payment_notifications WHERE order_no = ?",
                order.getOrderNo());
        assertThat(rows).isNotEmpty();
        String sanitized = String.valueOf(rows.get(0).get("params_sanitized"));
        assertThat(sanitized)
                .doesNotContain("sign=")
                .doesNotContain(signValue)
                .doesNotContain(YUSEN_KEY)
                .doesNotContain("fictional-merchant-key")
                .contains("out_trade_no=" + order.getOrderNo())
                .contains("money=5.90");
        assertThat(flag(rows.get(0).get("sign_valid"))).isEqualTo(1);
    }

    /** PAY-P1-010 */
    @Test
    void p1010_membershipRemainsAfterLogoutAndRelogin() throws Exception {
        String openid = "openid-p1010-" + UUID.randomUUID();
        Login firstLogin = login(openid);

        MvcResult create = mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstLogin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"YEARLY\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String orderNo = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("data").get("orderNo").asText();
        assertThat(orderStatus(orderNo)).isEqualTo("PENDING_PAYMENT");

        FakePaymentProvider.paid(orderNo, 1990);
        String ack = notificationService.handle(
                PaymentProviderCode.FAKE,
                FakePaymentProvider.signedNotification(orderNo, "19.90", "TRADE_SUCCESS"),
                "127.0.0.1");
        assertThat(ack).isEqualTo("success");
        awaitStatus(orderNo, "FULFILLED");
        assertThat(paymentGrantCount(orderNo)).isEqualTo(1);

        MvcResult meBefore = mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstLogin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        String expiresBefore = objectMapper.readTree(meBefore.getResponse().getContentAsString())
                .get("data").get("membershipExpiresAt").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstLogin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstLogin.token()))
                .andExpect(status().isUnauthorized());

        Login secondLogin = login(openid);
        assertThat(secondLogin.userId()).isEqualTo(firstLogin.userId());

        MvcResult meAfter = mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondLogin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        JsonNode after = objectMapper.readTree(meAfter.getResponse().getContentAsString()).get("data");
        assertThat(after.get("membershipExpiresAt").asText()).isEqualTo(expiresBefore);
        assertThat(paymentGrantCount(orderNo)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM membership_grants WHERE source_type = 'PAYMENT' AND source_ref = ?",
                Long.class, orderNo)).isEqualTo(firstLogin.userId());
    }

    private record Login(long userId, String token) {
    }

    private Login login(String openid) {
        try {
            String code = "code-" + openid + "-" + UUID.randomUUID();
            wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok(openid));
            MvcResult result = mockMvc.perform(post("/api/v1/auth/wechat/login")
                            .header("X-App-Code", APP)
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

    private PaymentOrderEntity createYusenOrder(long userId, PlanCode planCode) {
        var plan = planRepository.findByAppIdAndPlanCode(appId, planCode).orElseThrow();
        Instant now = TestConfig.CLOCK.instant();
        return orderRepository.saveAndFlush(PaymentOrderEntity.createPending(
                appId, userId, orderNoGenerator.next(now), "gap-" + UUID.randomUUID(), plan,
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

    private String sendYusenNotify(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/payment/notify/yusen");
        params.forEach(request::param);
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void awaitStatus(String orderNo, String expected) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(orderStatus(orderNo))) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
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

    private static int flag(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) value).intValue();
    }
}
