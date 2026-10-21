package com.aurora.appserver.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.appserver.support.ExternalMySqlSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class AccountAndTrialSchemaIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayV3CreatesFourAccountAndTrialTables() {
        assertTableExists("users");
        assertTableExists("wechat_identities");
        assertTableExists("auth_sessions");
        assertTableExists("device_trials");
    }

    @Test
    void usersHasCompositeUniqueOnAppIdAndId() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'users'
                  AND CONSTRAINT_NAME = 'uk_users_app_id_id'
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void compositeForeignKeysExistOnWechatAndSessions() {
        assertThat(constraintExists("wechat_identities", "fk_wechat_identities_user", "FOREIGN KEY")).isTrue();
        assertThat(constraintExists("auth_sessions", "fk_auth_sessions_user", "FOREIGN KEY")).isTrue();
        assertThat(constraintExists("device_trials", "fk_device_trials_app", "FOREIGN KEY")).isTrue();
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        assertThat(count).as("table %s", tableName).isEqualTo(1);
    }

    private boolean constraintExists(String tableName, String constraintName, String type) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND CONSTRAINT_NAME = ?
                  AND CONSTRAINT_TYPE = ?
                """,
                tableName,
                constraintName,
                type);
        return !rows.isEmpty();
    }
}
