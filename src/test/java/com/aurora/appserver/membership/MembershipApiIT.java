package com.aurora.appserver.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.domain.AuthSessionEntity;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.service.TokenHasher;
import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.membership.service.MembershipService.GrantCommand;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.trial.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestWechatAuthClient.Config.class, MembershipApiIT.ClockConfig.class})
class MembershipApiIT {

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestWechatAuthClient wechatAuthClient;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long appAId;
    private Long appBId;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
        ClockConfig.CLOCK.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        ensureSecondApp();
        appAId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_A);
        appBId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_B);
    }

    @Test
    void meReturnsNoneForNewUser() throws Exception {
        JsonNode login = login(APP_A, "mem-none-" + System.nanoTime());
        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.isLifetime").value(false))
                .andExpect(jsonPath("$.data.membershipExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist());
    }

    @Test
    void meReturnsActiveMembership() throws Exception {
        String openid = "mem-active-" + System.nanoTime();
        JsonNode login = login(APP_A, openid);
        long userId = login.get("userId").asLong();
        membershipService.grant(new GrantCommand(
                appAId, userId, PlanCode.YEARLY, GrantSourceType.ADMIN, "api-yearly-" + userId, null));

        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.planType").value("YEARLY"))
                .andExpect(jsonPath("$.data.isLifetime").value(false))
                .andExpect(jsonPath("$.data.membershipStartedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.membershipExpiresAt").value("2027-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.serverTime").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void appTokenMismatchRejected() throws Exception {
        JsonNode login = login(APP_A, "mem-mismatch-" + System.nanoTime());
        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_B)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get("/api/v1/membership/me").header("X-App-Code", APP_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void invalidTokenRejected() throws Exception {
        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenRejected() throws Exception {
        JsonNode login = login(APP_A, "mem-expired-" + System.nanoTime());
        String token = login.get("accessToken").asText();
        AuthSessionEntity session = authSessionRepository.findByTokenHash(tokenHasher.sha256Hex(token)).orElseThrow();
        jdbcTemplate.update(
                "UPDATE auth_sessions SET expires_at = ? WHERE id = ?",
                Instant.now().minus(1, ChronoUnit.HOURS),
                session.getId());

        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void disabledAppRejected() throws Exception {
        JsonNode login = login(APP_A, "mem-disabled-" + System.nanoTime());
        String token = login.get("accessToken").asText();
        jdbcTemplate.update("UPDATE apps SET status = 'DISABLED' WHERE app_code = ?", APP_A);
        try {
            mockMvc.perform(get("/api/v1/membership/me")
                            .header("X-App-Code", APP_A)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40002));
        } finally {
            jdbcTemplate.update("UPDATE apps SET status = 'ACTIVE' WHERE app_code = ?", APP_A);
        }
    }

    @Test
    void clientCannotGrantViaHttp() throws Exception {
        JsonNode login = login(APP_A, "mem-no-grant-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/membership/grant")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planType\":\"LIFETIME\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void clientCannotRevokeViaHttp() throws Exception {
        JsonNode login = login(APP_A, "mem-no-revoke-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/membership/revoke")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRef\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void clientCannotSpecifyExpiryOnMe() throws Exception {
        JsonNode login = login(APP_A, "mem-no-expiry-" + System.nanoTime());
        MvcResult result = mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText())
                        .param("membershipExpiresAt", "2099-01-01T00:00:00Z")
                        .param("status", "ACTIVE")
                        .param("isLifetime", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.isLifetime").value(false))
                .andExpect(jsonPath("$.data.membershipExpiresAt").doesNotExist())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.path("status").asText()).isEqualTo("NONE");
        assertThat(data.path("isLifetime").asBoolean()).isFalse();
    }

    @Test
    void crossAppMembershipNotVisible() throws Exception {
        String openid = "mem-cross-" + System.nanoTime();
        JsonNode loginA = login(APP_A, openid);
        membershipService.grant(new GrantCommand(
                appAId,
                loginA.get("userId").asLong(),
                PlanCode.LIFETIME,
                GrantSourceType.ADMIN,
                "cross-a-" + System.nanoTime(),
                null));

        wechatAuthClient.setDefaultHandler(c -> TestWechatAuthClient.ok(openid));
        JsonNode loginB = login(APP_B, openid);
        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP_B)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginB.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"));
    }

    private JsonNode login(String appCode, String openidSuffix) throws Exception {
        String code = "code-" + openidSuffix;
        wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok("openid-" + openidSuffix));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", appCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
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
}
