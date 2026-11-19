package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aurora.appserver.config.isolation.ProdEnvironmentGuard;
import com.aurora.appserver.config.isolation.ProdIsolationRules;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** B3-06-A1: prod isolation (configuration stage and connected-database stage). */
class ProdIsolationRulesTest {

    private static final String GOOD_URL =
            "jdbc:mysql://db.prod.example.com:3306/aurora_app_prod"
                    + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=REQUIRED";

    private static MockEnvironment valid() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.url", GOOD_URL);
        env.setProperty("server.port", "8082");
        env.setProperty("aurora.payment.provider", "YUSEN");
        env.setProperty("aurora.payment.environment", "PRODUCTION");
        env.setProperty("aurora.payment.yusen.pid", "1001");
        env.setProperty("aurora.payment.yusen.key", "fictional-key-for-unit-tests");
        env.setProperty("aurora.payment.yusen.gateway-base-url", "https://ivo.hzlugun.top");
        env.setProperty("aurora.payment.yusen.notify-url", "https://aurora-api.appforfun.top/api/v1/payment/notify/yusen");
        env.setProperty("aurora.payment.yusen.return-url", "https://aurora-api.appforfun.top/api/v1/payment/return/yusen");
        env.setProperty("aurora.payment.yusen.network-enabled", "false");
        return env;
    }

    private static void guard(MockEnvironment env) {
        new ProdEnvironmentGuard().postProcessEnvironment(env, null);
    }

    @Test
    void t01_validProdConfigurationPasses() {
        assertThatCode(() -> guard(valid())).doesNotThrowAnyException();
    }

    @Test
    void t01_profileFilesResolveToProductionDefaultsWithoutSecrets() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        var base = loader.load("application", new ClassPathResource("application.yml"));
        var profile = loader.load("application-prod", new ClassPathResource("application-prod.yml"));
        Map<String, Object> deploymentEnv = new HashMap<>();
        deploymentEnv.put("PROD_DB_HOST", "db.prod.example.com");
        deploymentEnv.put("PROD_DB_USERNAME", "prod_app");
        deploymentEnv.put("PROD_DB_PASSWORD", "fictional-db-password");
        deploymentEnv.put("YUSEN_PID", "1001");
        deploymentEnv.put("YUSEN_KEY", "fictional-key-for-unit-tests");
        deploymentEnv.put("YUSEN_GATEWAY_URL", "https://ivo.hzlugun.top");
        deploymentEnv.put("YUSEN_NOTIFY_URL", "https://aurora-api.appforfun.top/api/v1/payment/notify/yusen");
        deploymentEnv.put("YUSEN_RETURN_URL", "https://aurora-api.appforfun.top/api/v1/payment/return/yusen");
        env.getPropertySources().addFirst(new MapPropertySource("deployment-env", deploymentEnv));
        env.getPropertySources().addLast(profile.get(0));
        base.forEach(ps -> env.getPropertySources().addLast(ps));

        new ProdEnvironmentGuard().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.url"))
                .contains("/aurora_app_prod?")
                .contains("sslMode=REQUIRED")
                .contains("connectionTimeZone=UTC")
                .contains("forceConnectionTimeZoneToSession=true");
        assertThat(env.getProperty("server.port")).isEqualTo("8082");
        assertThat(env.getProperty("aurora.payment.provider")).isEqualTo("YUSEN");
        assertThat(env.getProperty("aurora.payment.environment")).isEqualTo("PRODUCTION");
        assertThat(env.getProperty("aurora.payment.yusen.network-enabled")).isEqualTo("false");
        assertThat(env.getProperty("aurora.payment.reuse-pending-orders")).isEqualTo("false");
        assertThat(env.getProperty("aurora.payment.jobs.enabled")).isEqualTo("true");

        var raw = profile.get(0);
        assertThat(String.valueOf(raw.getProperty("spring.datasource.password"))).isEqualTo("${PROD_DB_PASSWORD}");
        assertThat(String.valueOf(raw.getProperty("spring.datasource.username"))).isEqualTo("${PROD_DB_USERNAME}");
    }

    @Test
    void t02_forbiddenDatabasesAreRejected() {
        for (String db : new String[] {"aurora_app", "aurora_app_test", "aurora_app_paystaging"}) {
            MockEnvironment env = valid();
            env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/" + db);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining(db + " belongs to another environment");
        }
    }

    @Test
    void t02b_jdbcUrlWithoutForceConnectionTimeZoneToSessionIsRejected() {
        MockEnvironment env = valid();
        env.setProperty(
                "spring.datasource.url",
                "jdbc:mysql://db.prod.example.com:3306/aurora_app_prod?connectionTimeZone=UTC&sslMode=REQUIRED");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("forceConnectionTimeZoneToSession=true");
    }

    @Test
    void t03_fakeOrNoneProviderIsRejected() {
        MockEnvironment fake = valid();
        fake.setProperty("aurora.payment.provider", "FAKE");
        assertThatThrownBy(() -> guard(fake)).hasMessageContaining("must be YUSEN (configured: FAKE)");
        MockEnvironment none = valid();
        none.setProperty("aurora.payment.provider", "NONE");
        assertThatThrownBy(() -> guard(none)).hasMessageContaining("configured: NONE");
    }

    @Test
    void t04_paymentEnvironmentMustBeProduction() {
        for (String wrong : new String[] {"TEST", "STAGING"}) {
            MockEnvironment env = valid();
            env.setProperty("aurora.payment.environment", wrong);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("must be PRODUCTION (configured: " + wrong + ")");
        }
    }

    @Test
    void t05_networkDefaultsOffAndInvalidGatewayCannotEnableNetwork() {
        MockEnvironment invalidGw = valid();
        invalidGw.setProperty("aurora.payment.yusen.gateway-base-url", "https://yusen-gateway-placeholder.invalid");
        assertThatThrownBy(() -> guard(invalidGw)).hasMessageContaining("public host name");

        MockEnvironment networkOnInvalid = valid();
        networkOnInvalid.setProperty("aurora.payment.yusen.gateway-base-url", "https://pay.example.invalid");
        networkOnInvalid.setProperty("aurora.payment.yusen.network-enabled", "true");
        assertThatThrownBy(() -> guard(networkOnInvalid))
                .hasMessageContaining("public host name")
                .message().doesNotContain("fictional-key-for-unit-tests");
    }

    @Test
    void t06_missingMerchantSettingsAreRejected() {
        String[][] required = {
                {"aurora.payment.yusen.pid", "YUSEN_PID"},
                {"aurora.payment.yusen.key", "YUSEN_KEY"},
                {"aurora.payment.yusen.gateway-base-url", "YUSEN_GATEWAY_URL"},
                {"aurora.payment.yusen.notify-url", "YUSEN_NOTIFY_URL"},
                {"aurora.payment.yusen.return-url", "YUSEN_RETURN_URL"}};
        for (String[] r : required) {
            MockEnvironment env = valid();
            env.setProperty(r[0], "");
            assertThatThrownBy(() -> guard(env)).hasMessageContaining(r[1]);
        }
    }

    @Test
    void t07_reservedPortsAndIncompatibleProfilesAreRejected() {
        for (String port : new String[] {"8080", "8081"}) {
            MockEnvironment env = valid();
            env.setProperty("server.port", port);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("server.port " + port);
        }
        for (String other : new String[] {"s32it", "test", "local", "paystaging"}) {
            MockEnvironment env = valid();
            env.setActiveProfiles("prod", other);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("'" + other + "' must not be combined");
        }
    }

    @Test
    void t08_otherProfilesAreUnaffectedByProdGuard() {
        MockEnvironment paystaging = new MockEnvironment();
        paystaging.setActiveProfiles("paystaging");
        paystaging.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/aurora_app_paystaging");
        assertThatCode(() -> guard(paystaging)).doesNotThrowAnyException();
    }

    @Test
    void t09_connectedDatabaseMustBeProd() throws SQLException {
        assertThatCode(() -> ProdIsolationRules.verifyConnectedDatabase(reporting("aurora_app_prod")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProdIsolationRules.verifyConnectedDatabase(reporting("aurora_app_paystaging")))
                .hasMessageContaining("connected database is aurora_app_paystaging");
        assertThatThrownBy(() -> ProdIsolationRules.verifyConnectedDatabase(reporting("aurora_app")))
                .hasMessageContaining("connected database is aurora_app");
    }

    @Test
    void t10_networkEnabledWithPublicGatewayIsAllowedAtConfigStage() {
        MockEnvironment env = valid();
        env.setProperty("aurora.payment.yusen.network-enabled", "true");
        assertThatCode(() -> guard(env)).doesNotThrowAnyException();
    }

    private static DataSource reporting(String database) throws SQLException {
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
