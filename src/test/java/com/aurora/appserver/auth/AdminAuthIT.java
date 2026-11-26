package com.aurora.appserver.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.service.AdminLoginRateLimiter;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthIT {

    private static final String APP = "calculator_vault";
    private static final String USERNAME = "cv_admin_test";
    private static final String PASSWORD = "test-admin-pass";
    private static final String PASSWORD_HASH;

    static {
        PASSWORD_HASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode(PASSWORD);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
        registry.add("aurora.admin.apps.calculator_vault.username", () -> USERNAME);
        registry.add("aurora.admin.apps.calculator_vault.password-hash", () -> PASSWORD_HASH);
        registry.add("aurora.admin.apps.calculator_vault.enabled", () -> "true");
        registry.add("aurora.admin.rate-limit-per-window", () -> "5");
        registry.add("aurora.admin.rate-limit-window", () -> "1m");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminLoginRateLimiter rateLimiter;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        assertThat(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).isTrue();
    }

    @Test
    void adminLogin_me_logout_andRejectsUserApis() throws Exception {
        JsonNode login = adminLogin(USERNAME, PASSWORD);
        String token = login.get("accessToken").asText();
        assertThat(login.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(login.get("username").asText()).isEqualTo(USERNAME);
        assertThat(login.get("appCode").asText()).isEqualTo(APP);
        assertThat(login.has("userId")).isFalse();

        mockMvc.perform(get("/api/v1/auth/admin/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.username").value(USERNAME));

        // Admin token must not unlock ordinary user endpoints.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/admin/logout")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/auth/admin/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_sessions WHERE admin_username = ? AND revoked_at IS NULL",
                Integer.class, USERNAME);
        assertThat(sessions).isZero();
    }

    @Test
    void wrongPassword_returnsAuthFailed_withoutCreatingSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .header("X-App-Code", APP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40018));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_sessions WHERE admin_username = ?",
                Integer.class, USERNAME);
        assertThat(count).isZero();
    }

    private JsonNode adminLogin(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/admin/login")
                        .header("X-App-Code", APP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
