package com.aurora.appserver.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.repository.WechatIdentityRepository;
import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AccountDeletionIT {

    private static final String APP = "calculator_vault";

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
    private UserRepository userRepository;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long appId;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
        appId = jdbcTemplate.queryForObject("SELECT id FROM apps WHERE app_code = ?", Long.class, APP);
    }

    @Test
    void deleteAccount_closesBindingSessionsMembership_andAllowsNewLoginWithoutOldVip() throws Exception {
        wechatAuthClient.onCode("code-del-1", c -> TestWechatAuthClient.ok("openid-account-delete"));
        JsonNode login = login(APP, "code-del-1");
        long userId = login.get("userId").asLong();
        String token = login.get("accessToken").asText();

        membershipService.grant(new MembershipService.GrantCommand(
                appId, userId, PlanCode.MONTHLY, GrantSourceType.ADMIN, "admin-del-" + userId, null));
        assertThat(membershipService.getStatus(appId, userId).status()).isEqualTo(MembershipStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/auth/account/delete")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(wechatIdentityRepository.countByAppIdAndUserId(appId, userId)).isZero();
        assertThat(authSessionRepository.countByAppIdAndUserId(appId, userId)).isZero();
        assertThat(userRepository.findByAppIdAndId(appId, userId)).isPresent()
                .get()
                .extracting(u -> u.getStatus())
                .isEqualTo(UserStatus.DISABLED);

        Integer activeGrants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM membership_grants WHERE app_id = ? AND user_id = ? AND revoked_at IS NULL",
                Integer.class, appId, userId);
        assertThat(activeGrants).isZero();
        Integer grantRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM membership_grants WHERE app_id = ? AND user_id = ?",
                Integer.class, appId, userId);
        assertThat(grantRows).isGreaterThan(0);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        wechatAuthClient.onCode("code-del-2", c -> TestWechatAuthClient.ok("openid-account-delete"));
        JsonNode again = login(APP, "code-del-2");
        long newUserId = again.get("userId").asLong();
        assertThat(newUserId).isNotEqualTo(userId);
        assertThat(membershipService.getStatus(appId, newUserId).status()).isEqualTo(MembershipStatus.NONE);
    }

    @Test
    void deleteAccount_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/auth/account/delete").header("X-App-Code", APP))
                .andExpect(status().isUnauthorized());
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
}
