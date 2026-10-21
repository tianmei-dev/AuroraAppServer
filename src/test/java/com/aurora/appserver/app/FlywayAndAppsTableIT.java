package com.aurora.appserver.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.app.domain.AppEntity;
import com.aurora.appserver.app.domain.AppStatus;
import com.aurora.appserver.app.domain.Platform;
import com.aurora.appserver.app.repository.AppRepository;
import com.aurora.appserver.support.ExternalMySqlSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class FlywayAndAppsTableIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppRepository appRepository;

    @Test
    void flywayCreatesAppsTableAndSeedsCalculatorVault() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'apps'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);

        AppEntity app = appRepository.findByAppCode("calculator_vault").orElseThrow();
        assertThat(app.getAppName()).isEqualTo("\u9690\u85cf\u8ba1\u7b97\u5668");
        assertThat(app.getPlatform()).isEqualTo(Platform.ANDROID);
        assertThat(app.getPackageName()).isEqualTo("com.aurora.calculatorvault");
        assertThat(app.getStatus()).isEqualTo(AppStatus.ACTIVE);
        assertThat(app.getId()).isNotNull();
        assertThat(app.getCreatedAt()).isNotNull();
        assertThat(app.getUpdatedAt()).isNotNull();
    }

    @Test
    void appCodeUniqueConstraintIsEnforcedByDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                "calculator_vault", "duplicate", "ANDROID", "com.aurora.other", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void platformPackageNameUniqueConstraintIsEnforcedByDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                "another_code", "duplicate package", "ANDROID", "com.aurora.calculatorvault", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusCheckConstraintRejectsInvalidValue() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                "bad_status_app", "Bad Status", "ANDROID", "com.aurora.badstatus", "UNKNOWN"))
                .isInstanceOfAny(DataIntegrityViolationException.class, org.springframework.jdbc.UncategorizedSQLException.class)
                .hasMessageContaining("chk_apps_status");
    }

    @Test
    void tableUsesUtf8mb4() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'apps'");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("TABLE_COLLATION").toString()).startsWith("utf8mb4");
    }
}
