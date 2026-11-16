package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
 * B3-04-A2 PAY-P0-017: with PAYMENT_PROVIDER=NONE, create-order fails and writes no order row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aurora.payment.provider=NONE")
@Import(TestWechatAuthClient.Config.class)
class PaymentProviderNoneIT {

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
    void p0017_createOrderRejectedWhenProviderNoneAndNoOrderRow() throws Exception {
        String openid = "openid-p0017-" + UUID.randomUUID();
        String code = "code-" + openid;
        wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok(openid));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", APP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(login.getResponse().getContentAsString()).get("data");
        long userId = data.get("userId").asLong();
        String token = data.get("accessToken").asText();

        long before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", Long.class, userId);

        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"MONTHLY\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50003));

        long after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", Long.class, userId);
        assertThat(after).isEqualTo(before);

        long requests = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_order_requests WHERE user_id = ?", Long.class, userId);
        assertThat(requests).isZero();
    }
}
