package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.membership.service.MembershipService.GrantCommand;
import com.aurora.appserver.payment.support.FakePaymentProvider;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.trial.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestWechatAuthClient.Config.class, PaymentOrderApiIT.ClockConfig.class})
class PaymentOrderApiIT {

    private static final String APP_A = "calculator_vault";
    private static final String APP_B = "speaker_cleaner";
    private static final String OTHER_PLAN_APP = "payment_it_other_app";
    private static final Instant T0 = Instant.parse("2026-11-06T13:00:00Z");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @TestConfiguration
    static class ClockConfig {
        static final MutableClock CLOCK = new MutableClock(T0);

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestWechatAuthClient wechatAuthClient;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long appAId;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
        ClockConfig.CLOCK.setInstant(T0);
        ensureApp(APP_B, "Speaker Cleaner", "com.aurora.speakercleaner");
        ensureApp(OTHER_PLAN_APP, "Payment IT Other", "com.aurora.payment.it.other");
        ensureOtherAppPlan();
        appAId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_A);
    }

    // ---------------------------------------------------------------- plans

    @Test
    void plansReturnServerDefinedPricesAndDurations() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/membership/plans").header("X-App-Code", APP_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode plans = data(result);
        assertThat(plans.size()).isEqualTo(3);

        assertPlan(plans.get(0), "MONTHLY", 590, "5.90", 30, false);
        assertPlan(plans.get(1), "YEARLY", 1990, "19.90", 365, false);
        assertPlan(plans.get(2), "LIFETIME", 2990, "29.90", null, true);
    }

    @Test
    void plansAreScopedToRequestedApp() throws Exception {
        JsonNode vaultPlans = data(mockMvc.perform(get("/api/v1/membership/plans").header("X-App-Code", APP_A))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode plan : vaultPlans) {
            assertThat(plan.get("priceFen").asInt()).isNotEqualTo(123);
        }

        JsonNode otherPlans = data(mockMvc.perform(get("/api/v1/membership/plans")
                        .header("X-App-Code", OTHER_PLAN_APP))
                .andExpect(status().isOk()).andReturn());
        assertThat(otherPlans.size()).isEqualTo(1);
        assertThat(otherPlans.get(0).get("priceFen").asInt()).isEqualTo(123);

        mockMvc.perform(get("/api/v1/membership/plans"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
        mockMvc.perform(get("/api/v1/membership/plans").header("X-App-Code", "no_such_app_xyz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ---------------------------------------------------------------- create

    @Test
    void createRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MONTHLY", newRequestId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MONTHLY", newRequestId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void threePlansSnapshotServerPriceAndDuration() throws Exception {
        Object[][] expectations = {
                {"MONTHLY", 590, "5.90", 30, 0},
                {"YEARLY", 1990, "19.90", 365, 0},
                {"LIFETIME", 2990, "29.90", null, 1},
        };
        for (Object[] e : expectations) {
            Login login = login(APP_A);
            JsonNode order = createOk(login, (String) e[0], newRequestId());
            assertThat(order.get("amountFen").asInt()).isEqualTo(e[1]);
            assertThat(order.get("amountDisplay").asText()).isEqualTo(e[2]);
            assertThat(order.get("currency").asText()).isEqualTo("CNY");
            assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
            assertThat(Instant.parse(order.get("expiresAt").asText())).isEqualTo(T0.plus(Duration.ofMinutes(30)));

            Map<String, Object> row = orderRow(order.get("orderNo").asText());
            assertThat(row.get("amount_fen")).isEqualTo(e[1]);
            assertThat(row.get("duration_days")).isEqualTo(e[3]);
            assertThat(flag(row.get("is_lifetime"))).isEqualTo(e[4]);
            assertThat(row.get("plan_code")).isEqualTo(e[0]);
        }
    }

    @Test
    void forgedAmountUserIdChannelAndDurationAreIgnored() throws Exception {
        Login login = login(APP_A);
        String forged = """
                {"planCode":"YEARLY","clientRequestId":"%s",
                 "amountFen":1,"amount":"0.01","money":"0.01","userId":999999,
                 "channel":"ALIPAY","type":"alipay","durationDays":9999,"isLifetime":true,
                 "status":"FULFILLED"}
                """.formatted(newRequestId());
        JsonNode order = data(mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(order.get("amountFen").asInt()).isEqualTo(1990);
        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        // Fake provider: test payment instruction only, never a (fake) website link.
        assertThat(order.get("pay").get("kind").asText()).isEqualTo("TEST");
        assertThat(order.get("pay").has("value")).isFalse();
        String orderNo = order.get("orderNo").asText();
        assertThat(orderNo).matches("^CV\\d{14}[A-Z2-7]{12}$");

        Map<String, Object> row = orderRow(orderNo);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(login.userId());
        assertThat(((Number) row.get("app_id")).longValue()).isEqualTo(appAId);
        assertThat(row.get("amount_fen")).isEqualTo(1990);
        assertThat(row.get("duration_days")).isEqualTo(365);
        assertThat(flag(row.get("is_lifetime"))).isZero();
        assertThat(row.get("provider")).isEqualTo("FAKE");
        assertThat(row.get("environment")).isEqualTo("TEST");
        assertThat(row.get("merchant_pid")).isEqualTo(FakePaymentProvider.FAKE_PID);

        List<String> events = jdbcTemplate.queryForList(
                "SELECT trigger_source FROM payment_order_events WHERE order_id = ? ORDER BY id",
                String.class, ((Number) row.get("id")).longValue());
        assertThat(events).containsExactly("CREATE");
    }

    @Test
    void invalidInputsAreRejected() throws Exception {
        Login login = login(APP_A);
        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PLATINUM", newRequestId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MONTHLY", "bad id!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004));

        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"MONTHLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004));

        assertThat(orderCount(login.userId())).isZero();
    }

    @Test
    void lifetimeMemberCannotPurchaseAgain() throws Exception {
        Login login = login(APP_A);
        membershipService.grant(new GrantCommand(
                appAId, login.userId(), PlanCode.LIFETIME, GrantSourceType.ADMIN,
                "pay-it-lifetime-" + System.nanoTime(), null));

        for (String plan : List.of("MONTHLY", "YEARLY", "LIFETIME")) {
            mockMvc.perform(post("/api/v1/payment/orders")
                            .header("X-App-Code", APP_A)
                            .header(HttpHeaders.AUTHORIZATION, login.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(plan, newRequestId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40012));
        }
        assertThat(orderCount(login.userId())).isZero();
    }

    @Test
    void sameClientRequestIdIsStrictlyIdempotent() throws Exception {
        Login login = login(APP_A);
        String requestId = newRequestId();
        JsonNode first = createOk(login, "MONTHLY", requestId);
        JsonNode second = createOk(login, "MONTHLY", requestId);

        assertThat(second.get("orderNo").asText()).isEqualTo(first.get("orderNo").asText());
        assertThat(second.get("pay")).isEqualTo(first.get("pay"));
        assertThat(second.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());
        assertThat(orderCount(login.userId())).isEqualTo(1);
        assertThat(requestCount(login.userId())).isEqualTo(1);
    }

    @Test
    void sameClientRequestIdWithDifferentPlanConflicts() throws Exception {
        Login login = login(APP_A);
        String requestId = newRequestId();
        createOk(login, "MONTHLY", requestId);

        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("YEARLY", requestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40011));
        assertThat(orderCount(login.userId())).isEqualTo(1);
    }

    @Test
    void pendingOrderIsReusedForSamePlanAndReuseIsAlsoIdempotent() throws Exception {
        Login login = login(APP_A);
        JsonNode first = createOk(login, "YEARLY", newRequestId());

        ClockConfig.CLOCK.advance(Duration.ofMinutes(10));
        String reuseRequestId = newRequestId();
        JsonNode reused = createOk(login, "YEARLY", reuseRequestId);
        assertThat(reused.get("orderNo").asText()).isEqualTo(first.get("orderNo").asText());
        assertThat(reused.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());

        JsonNode otherPlan = createOk(login, "MONTHLY", newRequestId());
        assertThat(otherPlan.get("orderNo").asText()).isNotEqualTo(first.get("orderNo").asText());

        // After expiry the reusing clientRequestId still maps to the same (now expired) order.
        ClockConfig.CLOCK.advance(Duration.ofMinutes(25));
        JsonNode replay = createOk(login, "YEARLY", reuseRequestId);
        assertThat(replay.get("orderNo").asText()).isEqualTo(first.get("orderNo").asText());
        assertThat(replay.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(replay.has("pay")).isFalse();

        assertThat(orderCount(login.userId())).isEqualTo(2);
        long firstId = ((Number) orderRow(first.get("orderNo").asText()).get("id")).longValue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT trigger_source FROM payment_order_events WHERE order_id = ? ORDER BY id",
                String.class, firstId)).containsExactly("CREATE", "REUSE");
    }

    @Test
    void pendingOrderNearExpiryIsNotReused() throws Exception {
        Login login = login(APP_A);
        JsonNode first = createOk(login, "MONTHLY", newRequestId());

        ClockConfig.CLOCK.advance(Duration.ofMinutes(26));
        JsonNode fresh = createOk(login, "MONTHLY", newRequestId());
        assertThat(fresh.get("orderNo").asText()).isNotEqualTo(first.get("orderNo").asText());
        assertThat(Instant.parse(fresh.get("expiresAt").asText()))
                .isEqualTo(T0.plus(Duration.ofMinutes(26 + 30)));
    }

    @Test
    void expiredOrderReplayAndNewRequestAfterExpiry() throws Exception {
        Login login = login(APP_A);
        String requestId = newRequestId();
        JsonNode first = createOk(login, "MONTHLY", requestId);
        String orderNo = first.get("orderNo").asText();

        ClockConfig.CLOCK.advance(Duration.ofMinutes(31));

        JsonNode replay = createOk(login, "MONTHLY", requestId);
        assertThat(replay.get("orderNo").asText()).isEqualTo(orderNo);
        assertThat(replay.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(replay.has("pay")).isFalse();

        JsonNode fetched = getOk(login, APP_A, orderNo);
        assertThat(fetched.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(fetched.has("pay")).isFalse();

        JsonNode next = createOk(login, "MONTHLY", newRequestId());
        assertThat(next.get("orderNo").asText()).isNotEqualTo(orderNo);
        assertThat(next.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(next.has("pay")).isTrue();
    }

    @Test
    void concurrentSamePlanDifferentRequestIdsYieldOneOrder() throws Exception {
        Login login = login(APP_A);
        int threads = 8;
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            requestIds.add(newRequestId());
        }
        Set<String> orderNos = runConcurrently(threads, i -> createOk(login, "YEARLY", requestIds.get(i)));

        assertThat(orderNos).hasSize(1);
        assertThat(orderCount(login.userId())).isEqualTo(1);
        assertThat(requestCount(login.userId())).isEqualTo(threads);
    }

    @Test
    void concurrentSameRequestIdYieldsOneOrder() throws Exception {
        Login login = login(APP_A);
        String requestId = newRequestId();
        Set<String> orderNos = runConcurrently(6, i -> createOk(login, "MONTHLY", requestId));

        assertThat(orderNos).hasSize(1);
        assertThat(orderCount(login.userId())).isEqualTo(1);
        assertThat(requestCount(login.userId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- read & isolation

    @Test
    void getOrderReturnsPayInfoWhilePendingAndRejectsBadOrderNo() throws Exception {
        Login login = login(APP_A);
        JsonNode created = createOk(login, "LIFETIME", newRequestId());
        JsonNode fetched = getOk(login, APP_A, created.get("orderNo").asText());

        assertThat(fetched.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(fetched.get("amountFen").asInt()).isEqualTo(2990);
        assertThat(fetched.get("pay").get("kind").asText()).isEqualTo("TEST");
        assertThat(fetched.get("pay")).isEqualTo(created.get("pay"));
        assertThat(fetched.has("paidAt")).isFalse();

        mockMvc.perform(get("/api/v1/payment/orders/{orderNo}", "bad-order-no")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40403));
    }

    @Test
    void ordersAreIsolatedAcrossUsersAndApps() throws Exception {
        Login alice = login(APP_A);
        Login bob = login(APP_A);
        String sharedRequestId = newRequestId();
        JsonNode aliceOrder = createOk(alice, "MONTHLY", sharedRequestId);
        String orderNo = aliceOrder.get("orderNo").asText();

        mockMvc.perform(get("/api/v1/payment/orders/{orderNo}", orderNo)
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, bob.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40403));

        JsonNode bobOrder = createOk(bob, "MONTHLY", sharedRequestId);
        assertThat(bobOrder.get("orderNo").asText()).isNotEqualTo(orderNo);

        Login appBUser = login(APP_B);
        mockMvc.perform(get("/api/v1/payment/orders/{orderNo}", orderNo)
                        .header("X-App-Code", APP_B)
                        .header(HttpHeaders.AUTHORIZATION, appBUser.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40403));

        mockMvc.perform(get("/api/v1/payment/orders/{orderNo}", orderNo)
                        .header("X-App-Code", APP_B)
                        .header(HttpHeaders.AUTHORIZATION, alice.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ---------------------------------------------------------------- environment isolation

    @Test
    void databaseRejectsFakeProviderOrdersInProduction() throws Exception {
        Login login = login(APP_A);
        JsonNode order = createOk(login, "MONTHLY", newRequestId());
        long orderId = ((Number) orderRow(order.get("orderNo").asText()).get("id")).longValue();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payment_orders SET environment = 'PRODUCTION' WHERE id = ?", orderId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_payment_orders_no_fake_in_prod");
        assertThat(orderRow(order.get("orderNo").asText()).get("environment")).isEqualTo("TEST");
    }

    // ---------------------------------------------------------------- helpers

    private record Login(long userId, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    @FunctionalInterface
    private interface IndexedCall {
        JsonNode call(int index) throws Exception;
    }

    private Set<String> runConcurrently(int threads, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<JsonNode> task = () -> {
                    start.await();
                    return call.call(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            Set<String> orderNos = new HashSet<>();
            for (Future<JsonNode> f : futures) {
                orderNos.add(f.get(60, TimeUnit.SECONDS).get("orderNo").asText());
            }
            return orderNos;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode createOk(Login login, String planCode, String requestId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(planCode, requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    private JsonNode getOk(Login login, String appCode, String orderNo) throws Exception {
        return data(mockMvc.perform(get("/api/v1/payment/orders/{orderNo}", orderNo)
                        .header("X-App-Code", appCode)
                        .header(HttpHeaders.AUTHORIZATION, login.bearer()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Login login(String appCode) throws Exception {
        String suffix = "pay-it-" + UUID.randomUUID();
        String code = "code-" + suffix;
        wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok("openid-" + suffix));
        JsonNode data = data(mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", appCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
        return new Login(data.get("userId").asLong(), data.get("accessToken").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String body(String planCode, String requestId) {
        return "{\"planCode\":\"" + planCode + "\",\"clientRequestId\":\"" + requestId + "\"}";
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** MySQL Connector/J maps TINYINT(1) to Boolean. */
    private static int flag(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) value).intValue();
    }

    private Map<String, Object> orderRow(String orderNo) {
        return jdbcTemplate.queryForMap("SELECT * FROM payment_orders WHERE order_no = ?", orderNo);
    }

    private long orderCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE app_id = ? AND user_id = ?", Long.class, appAId, userId);
    }

    private long requestCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_order_requests WHERE app_id = ? AND user_id = ?",
                Long.class, appAId, userId);
    }

    private static void assertPlan(
            JsonNode plan, String code, int priceFen, String display, Integer days, boolean lifetime) {
        assertThat(plan.get("planCode").asText()).isEqualTo(code);
        assertThat(plan.get("priceFen").asInt()).isEqualTo(priceFen);
        assertThat(plan.get("priceDisplay").asText()).isEqualTo(display);
        assertThat(plan.get("isLifetime").asBoolean()).isEqualTo(lifetime);
        if (days == null) {
            assertThat(plan.has("durationDays")).isFalse();
        } else {
            assertThat(plan.get("durationDays").asInt()).isEqualTo(days);
        }
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

    private void ensureOtherAppPlan() {
        Long appId = jdbcTemplate.queryForObject(
                "SELECT id FROM apps WHERE app_code = ?", Long.class, OTHER_PLAN_APP);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM membership_plans WHERE app_id = ?", Integer.class, appId);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status) "
                            + "VALUES (?, 'MONTHLY', 'IT月卡', 123, 30, 0, 'ACTIVE')",
                    appId);
        }
    }
}
