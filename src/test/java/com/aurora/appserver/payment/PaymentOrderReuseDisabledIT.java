package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * I3.5.5-B1 T09/T10 with reuse switched OFF (paystaging default). The reuse-ON behaviour is
 * covered by PaymentOrderApiIT (default configuration). Runs on aurora_app_test only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aurora.payment.reuse-pending-orders=false")
@Import(TestWechatAuthClient.Config.class)
class PaymentOrderReuseDisabledIT {

    private static final String APP = "calculator_vault";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestWechatAuthClient wechatAuthClient;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
    }

    @Test
    void t09_newRequestIdCreatesNewOrderInsteadOfReusingPendingOne() throws Exception {
        Login login = login();
        JsonNode first = create(login, "MONTHLY", UUID.randomUUID().toString());
        JsonNode second = create(login, "MONTHLY", UUID.randomUUID().toString());

        assertThat(second.get("orderNo").asText()).isNotEqualTo(first.get("orderNo").asText());
        assertThat(first.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(second.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(count("SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", login.userId())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM payment_order_events e JOIN payment_orders o ON o.id = e.order_id "
                + "WHERE o.user_id = ? AND e.trigger_source = 'REUSE'", login.userId())).isZero();
    }

    @Test
    void t10_sameRequestIdStaysIdempotentWithReuseDisabled() throws Exception {
        Login login = login();
        String requestId = UUID.randomUUID().toString();
        JsonNode first = create(login, "YEARLY", requestId);
        JsonNode replay = create(login, "YEARLY", requestId);
        assertThat(replay.get("orderNo").asText()).isEqualTo(first.get("orderNo").asText());

        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MONTHLY", requestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40011));

        assertThat(count("SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", login.userId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payment_order_requests WHERE user_id = ?", login.userId())).isEqualTo(1);
    }

    @Test
    void t10_concurrentSameRequestIdYieldsOneOrderWithReuseDisabled() throws Exception {
        Login login = login();
        String requestId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Callable<String> task = () -> {
                    start.await();
                    return create(login, "LIFETIME", requestId).get("orderNo").asText();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            Set<String> orderNos = new HashSet<>();
            for (Future<String> f : futures) {
                orderNos.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(orderNos).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", login.userId())).isEqualTo(1);
    }

    private record Login(long userId, String token) {
    }

    private Login login() throws Exception {
        String suffix = "reuse-off-" + UUID.randomUUID();
        String code = "code-" + suffix;
        wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok("openid-" + suffix));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", APP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return new Login(data.get("userId").asLong(), data.get("accessToken").asText());
    }

    private JsonNode create(Login login, String planCode, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(planCode, requestId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static String body(String planCode, String requestId) {
        return "{\"planCode\":\"" + planCode + "\",\"clientRequestId\":\"" + requestId + "\"}";
    }

    private long count(String sql, long userId) {
        return jdbcTemplate.queryForObject(sql, Long.class, userId);
    }
}
