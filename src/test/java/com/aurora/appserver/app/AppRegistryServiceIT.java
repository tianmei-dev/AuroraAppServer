package com.aurora.appserver.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.support.ExternalMySqlSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class AppRegistryServiceIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired
    private AppRegistryService appRegistryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void activeAppCanBeQueried() {
        assertThat(appRegistryService.exists("calculator_vault")).isTrue();
        assertThat(appRegistryService.isActive("calculator_vault")).isTrue();

        Long appId = appRegistryService.getAppId("calculator_vault");
        assertThat(appId).isPositive();

        AppContext ctx = appRegistryService.requireActiveApp("calculator_vault");
        assertThat(ctx.appId()).isEqualTo(appId);
        assertThat(ctx.appCode()).isEqualTo("calculator_vault");
        assertThat(ctx.isActive()).isTrue();
    }

    @Test
    @Transactional
    void disabledAppCannotBeTreatedAsAvailable() {
        String appCode = "disabled_app_" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                appCode, "Disabled App", "ANDROID", "com.aurora.disabled." + System.nanoTime(), "DISABLED");

        assertThat(appRegistryService.exists(appCode)).isTrue();
        assertThat(appRegistryService.isActive(appCode)).isFalse();

        assertThatThrownBy(() -> appRegistryService.requireActiveApp(appCode))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.APP_DISABLED));
    }

    @Test
    void unknownAppCodeReturnsAppNotFound() {
        assertThat(appRegistryService.exists("does_not_exist")).isFalse();
        assertThat(appRegistryService.isActive("does_not_exist")).isFalse();

        assertThatThrownBy(() -> appRegistryService.findByAppCode("does_not_exist"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.APP_NOT_FOUND));

        assertThatThrownBy(() -> appRegistryService.requireActiveApp("does_not_exist"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.APP_NOT_FOUND));
    }

    @Test
    void missingAppContextReturnsAppContextRequired() {
        assertThatThrownBy(() -> appRegistryService.requireActiveApp(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.APP_CONTEXT_REQUIRED));

        assertThatThrownBy(() -> appRegistryService.requireActiveApp("  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.APP_CONTEXT_REQUIRED));

        assertThatThrownBy(() -> appRegistryService.resolveOptional(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.APP_CONTEXT_REQUIRED));
    }
}
