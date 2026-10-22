package com.aurora.appserver.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.domain.AuthSessionEntity;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.repository.WechatIdentityRepository;
import com.aurora.appserver.auth.service.TokenHasher;
import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestWechatAuthClient.Config.class)
class WechatAuthIT {

    private static final String APP_A = "calculator_vault";
    private static final String APP_B = "speaker_cleaner";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestWechatAuthClient wechatAuthClient;

    @Autowired
    private WechatIdentityRepository wechatIdentityRepository;

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
        ensureSecondApp();
        appAId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_A);
        appBId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP_B);
    }

    @Test
    void firstLoginCreatesUserAndReturnsToken() throws Exception {
        wechatAuthClient.onCode("code-new", c -> TestWechatAuthClient.ok("openid-new-user"));

        JsonNode data = login(APP_A, "code-new");
        assertThat(data.get("accessToken").asText()).isNotBlank();
        assertThat(data.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.get("userId").asLong()).isPositive();
        assertThat(data.get("appCode").asText()).isEqualTo(APP_A);
        assertThat(data.has("openid")).isFalse();
        assertThat(data.has("unionid")).isFalse();

        assertThat(wechatIdentityRepository.existsByAppIdAndWechatAppidAndOpenid(
                appAId, "wx_test_calculator", "openid-new-user")).isTrue();
    }

    @Test
    void repeatLoginRestoresSameUser() throws Exception {
        wechatAuthClient.onCode("code-repeat", c -> TestWechatAuthClient.ok("openid-repeat"));
        JsonNode first = login(APP_A, "code-repeat");
        JsonNode second = login(APP_A, "code-repeat");
        assertThat(second.get("userId").asLong()).isEqualTo(first.get("userId").asLong());
        assertThat(wechatIdentityRepository.countByAppIdAndUserId(appAId, first.get("userId").asLong()))
                .isEqualTo(1);
    }

    @Test
    void sameWechatOpenIdCreatesIndependentUsersAcrossApps() throws Exception {
        wechatAuthClient.setDefaultHandler(c -> TestWechatAuthClient.ok("openid-shared"));
        JsonNode userA = login(APP_A, "code-a");
        JsonNode userB = login(APP_B, "code-b");
        assertThat(userA.get("userId").asLong()).isNotEqualTo(userB.get("userId").asLong());
        assertThat(userA.get("appCode").asText()).isEqualTo(APP_A);
        assertThat(userB.get("appCode").asText()).isEqualTo(APP_B);
    }

    @Test
    void invalidWechatCodeIsRejected() throws Exception {
        wechatAuthClient.onCode("bad-code", TestWechatAuthClient.invalidCode());
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40005));
    }

    @Test
    void wechatServiceFailureIsRejected() throws Exception {
        wechatAuthClient.onCode("fail-code", TestWechatAuthClient.authFailed());
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fail-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40006));
    }

    @Test
    void missingWechatConfigReturnsServerError() throws Exception {
        String missingApp = "missing_app_" + System.nanoTime();
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", missingApp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"any\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40001));

        String noConfigApp = "no_wx_cfg_" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                noConfigApp, "No Config", "ANDROID", "com.aurora.noconfig." + System.nanoTime(), "ACTIVE");

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", noConfigApp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"any\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001));
    }

    @Test
    void disabledAppRejectsLogin() throws Exception {
        jdbcTemplate.update("UPDATE apps SET status = 'DISABLED' WHERE app_code = ?", APP_B);
        try {
            mockMvc.perform(post("/api/v1/auth/wechat/login")
                            .header("X-App-Code", APP_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40002));
        } finally {
            jdbcTemplate.update("UPDATE apps SET status = 'ACTIVE' WHERE app_code = ?", APP_B);
        }
    }

    @Test
    void meWorksWithValidToken() throws Exception {
        wechatAuthClient.onCode("code-me", c -> TestWechatAuthClient.ok("openid-me"));
        JsonNode login = login(APP_A, "code-me");
        String token = login.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(login.get("userId").asLong()))
                .andExpect(jsonPath("$.data.appCode").value(APP_A))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.openid").doesNotExist());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        wechatAuthClient.onCode("code-expired", c -> TestWechatAuthClient.ok("openid-expired"));
        JsonNode login = login(APP_A, "code-expired");
        String token = login.get("accessToken").asText();
        String hash = tokenHasher.sha256Hex(token);
        AuthSessionEntity session = authSessionRepository.findByTokenHash(hash).orElseThrow();
        jdbcTemplate.update(
                "UPDATE auth_sessions SET expires_at = ? WHERE id = ?",
                Instant.now().minus(1, ChronoUnit.MINUTES),
                session.getId());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        wechatAuthClient.onCode("code-logout", c -> TestWechatAuthClient.ok("openid-logout"));
        JsonNode login = login(APP_A, "code-logout");
        String token = login.get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    void appATokenCannotAccessAppB() throws Exception {
        wechatAuthClient.onCode("code-cross", c -> TestWechatAuthClient.ok("openid-cross"));
        JsonNode login = login(APP_A, "code-cross");
        String token = login.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP_B)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void disabledUserCannotAccessMe() throws Exception {
        String openid = "openid-disabled-user-" + System.nanoTime();
        wechatAuthClient.onCode("code-disabled-user", c -> TestWechatAuthClient.ok(openid));
        JsonNode login = login(APP_A, "code-disabled-user");
        String token = login.get("accessToken").asText();
        Long userId = login.get("userId").asLong();
        jdbcTemplate.update("UPDATE users SET status = 'DISABLED' WHERE id = ? AND app_id = ?", userId, appAId);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP_A)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void concurrentFirstLoginDoesNotCreateDuplicateIdentities() throws Exception {
        String openid = "openid-concurrent";
        wechatAuthClient.setDefaultHandler(c -> TestWechatAuthClient.ok(openid));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                JsonNode data = login(APP_A, "code-concurrent-" + Thread.currentThread().threadId());
                return data.get("userId").asLong();
            }));
        }
        start.countDown();
        List<Long> userIds = new ArrayList<>();
        for (Future<Long> future : futures) {
            userIds.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(userIds.stream().distinct().count()).isEqualTo(1);
        assertThat(wechatIdentityRepository.countByAppIdAndUserId(appAId, userIds.getFirst())).isEqualTo(1);
        Long identityCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wechat_identities WHERE app_id = ? AND openid = ?",
                Long.class,
                appAId,
                openid);
        assertThat(identityCount).isEqualTo(1);
    }

    @Test
    void tokenIsStoredAsHashOnly() throws Exception {
        wechatAuthClient.onCode("code-hash", c -> TestWechatAuthClient.ok("openid-hash"));
        JsonNode login = login(APP_A, "code-hash");
        String rawToken = login.get("accessToken").asText();
        String expectedHash = tokenHasher.sha256Hex(rawToken);

        AuthSessionEntity session = authSessionRepository.findByTokenHash(expectedHash).orElseThrow();
        assertThat(session.getTokenHash()).isEqualTo(expectedHash);
        assertThat(session.getTokenHash()).isNotEqualTo(rawToken);

        Integer plaintextHits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?",
                Integer.class,
                rawToken);
        assertThat(plaintextHits).isZero();
    }

    @Test
    void unauthorizedMeIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-App-Code", APP_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private JsonNode login(String appCode, String code) throws Exception {
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
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apps WHERE app_code = ?", Integer.class, APP_B);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                    "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                    APP_B, "Speaker Cleaner", "ANDROID", "com.aurora.speakercleaner", "ACTIVE");
        } else {
            jdbcTemplate.update("UPDATE apps SET status = 'ACTIVE' WHERE app_code = ?", APP_B);
        }
    }
}
