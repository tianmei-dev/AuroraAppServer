package com.aurora.appserver.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.auth.support.TestWechatAuthClient;
import com.aurora.appserver.support.ExternalMySqlSupport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.aurora.appserver.AuroraAppServerApplication;

/**
 * B3-04-C1: Boot must not create/log a default security user; HTTP Basic must not open an auth path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestWechatAuthClient.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class DefaultSecurityUserDisabledIT {

    private static final String APP = "calculator_vault";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private TestWechatAuthClient wechatAuthClient;

    @BeforeEach
    void setUp() {
        wechatAuthClient.reset();
    }

    @Test
    void userDetailsServiceAutoConfigurationIsExcluded() {
        var springBoot = AnnotationUtils.findAnnotation(
                AuroraAppServerApplication.class, org.springframework.boot.autoconfigure.SpringBootApplication.class);
        assertThat(springBoot).isNotNull();
        assertThat(springBoot.exclude()).contains(UserDetailsServiceAutoConfiguration.class);
        assertThat(applicationContext.getBeanNamesForType(UserDetailsService.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(InMemoryUserDetailsManager.class)).isEmpty();
    }

    @Test
    void startupDoesNotLogGeneratedSecurityPassword(CapturedOutput output) {
        // With UserDetailsServiceAutoConfiguration excluded, Boot must never emit the default-password line.
        assertThat(output.getAll()).doesNotContain("Using generated security password");
        assertThat(output.getAll()).doesNotContain("This generated password is for development use only");
    }

    @Test
    void httpBasicWithDefaultUsernameIsRejected() throws Exception {
        String basic = Base64.getEncoder().encodeToString("user:anything".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/api/v1/membership/me")
                        .header("X-App-Code", APP)
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void unauthorizedRequestsStillRequireBearerAuth() throws Exception {
        mockMvc.perform(get("/api/v1/membership/me").header("X-App-Code", APP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(post("/api/v1/payment/orders")
                        .header("X-App-Code", APP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"MONTHLY\",\"clientRequestId\":\"" + UUID.randomUUID()
                                + "\",\"payChannel\":\"WECHAT_PAY\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void wechatLoginStillWorksWithoutDefaultUser() throws Exception {
        String code = "code-c1-" + UUID.randomUUID();
        wechatAuthClient.onCode(code, c -> TestWechatAuthClient.ok("openid-c1-" + UUID.randomUUID()));
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-App-Code", APP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}
