package com.aurora.appserver.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.domain.AppStatus;
import com.aurora.appserver.app.domain.Platform;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.auth.domain.AdminSessionEntity;
import com.aurora.appserver.auth.service.AdminLoginRateLimiter;
import com.aurora.appserver.auth.service.AdminLoginService;
import com.aurora.appserver.auth.service.AdminSessionService;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminLoginServiceTest {

    @Mock
    private AppRegistryService appRegistryService;
    @Mock
    private AdminSessionService adminSessionService;
    @Mock
    private AdminLoginRateLimiter rateLimiter;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AuroraAuthProperties properties;
    private AdminLoginService service;

    @BeforeEach
    void setUp() {
        properties = new AuroraAuthProperties();
        AuroraAuthProperties.AdminCredentials creds = new AuroraAuthProperties.AdminCredentials();
        creds.setUsername("cv_admin");
        creds.setPasswordHash(passwordEncoder.encode("secret"));
        creds.setEnabled(true);
        LinkedHashMap<String, AuroraAuthProperties.AdminCredentials> apps = new LinkedHashMap<>();
        apps.put("calculator_vault", creds);
        properties.getAdmin().setApps(apps);
        service = new AdminLoginService(
                appRegistryService, properties, passwordEncoder, adminSessionService, rateLimiter);
    }

    @Test
    void login_success_issuesAdminSessionOnly() {
        when(appRegistryService.requireActiveApp("calculator_vault")).thenReturn(cvApp());
        AdminSessionEntity session = AdminSessionEntity.create(1L, "cv_admin", "hash", Instant.parse("2026-12-01T00:00:00Z"));
        ReflectionTestUtils.setField(session, "id", 9L);
        when(adminSessionService.issueSession(1L, "cv_admin"))
                .thenReturn(new AdminSessionService.IssuedAdminSession("tok", session));

        AdminLoginService.LoginResult result = service.login("calculator_vault", "cv_admin", "secret", "127.0.0.1");

        assertThat(result.accessToken()).isEqualTo("tok");
        assertThat(result.username()).isEqualTo("cv_admin");
        verify(adminSessionService).issueSession(1L, "cv_admin");
        verify(rateLimiter, never()).recordFailure(anyString());
    }

    @Test
    void login_wrongPassword_recordsFailure() {
        when(appRegistryService.requireActiveApp("calculator_vault")).thenReturn(cvApp());

        assertThatThrownBy(() -> service.login("calculator_vault", "cv_admin", "bad", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_AUTH_FAILED);
        verify(rateLimiter).recordFailure(anyString());
        verify(adminSessionService, never()).issueSession(anyLong(), anyString());
    }

    @Test
    void login_disabled_revokesSessions() {
        properties.getAdmin().getApps().get("calculator_vault").setEnabled(false);
        when(appRegistryService.requireActiveApp("calculator_vault")).thenReturn(cvApp());

        assertThatThrownBy(() -> service.login("calculator_vault", "cv_admin", "secret", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_DISABLED);
        verify(adminSessionService).revokeAllActiveSessions(eq(1L), eq("cv_admin"));
        verify(adminSessionService, never()).issueSession(anyLong(), anyString());
    }

    @Test
    void login_otherApp_forbidden() {
        when(appRegistryService.requireActiveApp("speaker_cleaner"))
                .thenReturn(new AppContext(2L, "speaker_cleaner", "Speaker", Platform.ANDROID,
                        "com.aurora.speakercleaner", AppStatus.ACTIVE));

        assertThatThrownBy(() -> service.login("speaker_cleaner", "cv_admin", "secret", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    private static AppContext cvApp() {
        return new AppContext(1L, "calculator_vault", "CV", Platform.ANDROID,
                "com.aurora.calculatorvault", AppStatus.ACTIVE);
    }
}
