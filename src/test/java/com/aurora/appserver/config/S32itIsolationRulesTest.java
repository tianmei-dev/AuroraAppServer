package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aurora.appserver.config.isolation.S32itEnvironmentGuard;
import com.aurora.appserver.config.isolation.S32itIsolationRules;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class S32itIsolationRulesTest {

    private static final String TEST_URL =
            "jdbc:mysql://127.0.0.1:3307/aurora_app_test?useUnicode=true&serverTimezone=UTC";

    private static MockEnvironment s32it(String url, String provider, String paymentEnvironment) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("s32it");
        if (url != null) {
            env.setProperty("spring.datasource.url", url);
        }
        if (provider != null) {
            env.setProperty("aurora.payment.provider", provider);
        }
        if (paymentEnvironment != null) {
            env.setProperty("aurora.payment.environment", paymentEnvironment);
        }
        return env;
    }

    private static void guard(MockEnvironment env) {
        new S32itEnvironmentGuard().postProcessEnvironment(env, null);
    }

    @Test
    void acceptsIsolatedConfiguration() {
        assertThatCode(() -> guard(s32it(TEST_URL, "FAKE", "TEST"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsDevelopmentOrDefaultDatabase() {
        assertThatThrownBy(() -> guard(s32it(
                "jdbc:mysql://127.0.0.1:3308/aurora_app?useUnicode=true", "FAKE", "TEST")))
                .hasMessageContaining("aurora_app_test").hasMessageContaining("configured: aurora_app");
        assertThatThrownBy(() -> guard(s32it(
                "jdbc:mysql://127.0.0.1:3306/aurora_app_test_copy", "FAKE", "TEST")))
                .hasMessageContaining("aurora_app_test_copy");
        assertThatThrownBy(() -> guard(s32it(null, "FAKE", "TEST")))
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsNonFakeProviderOrNonTestEnvironment() {
        assertThatThrownBy(() -> guard(s32it(TEST_URL, null, "TEST"))).hasMessageContaining("NONE");
        assertThatThrownBy(() -> guard(s32it(TEST_URL, "YUSEN", "TEST"))).hasMessageContaining("YUSEN");
        assertThatThrownBy(() -> guard(s32it(TEST_URL, "FAKE", null))).hasMessageContaining("PRODUCTION");
        assertThatThrownBy(() -> guard(s32it(TEST_URL, "FAKE", "STAGING"))).hasMessageContaining("STAGING");
    }

    @Test
    void otherProfilesAreNotAffected() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        local.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3308/aurora_app");
        assertThatCode(() -> guard(local)).doesNotThrowAnyException();
    }

    @Test
    void connectedDatabaseMustBeTheTestDatabase() throws SQLException {
        assertThatCode(() -> S32itIsolationRules.verifyConnectedDatabase(dataSourceReporting("aurora_app_test")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> S32itIsolationRules.verifyConnectedDatabase(dataSourceReporting("aurora_app")))
                .hasMessageContaining("connected database is aurora_app");

        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("down"));
        assertThatThrownBy(() -> S32itIsolationRules.verifyConnectedDatabase(broken))
                .hasMessageContaining("cannot verify");
    }

    private static DataSource dataSourceReporting(String database) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE()")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(database);
        return dataSource;
    }
}
