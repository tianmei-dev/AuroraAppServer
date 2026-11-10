package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aurora.appserver.config.isolation.PaystagingEnvironmentGuard;
import com.aurora.appserver.config.isolation.PaystagingIsolationRules;
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

/** I3.5.5-B1: paystaging isolation (configuration stage and connected-database stage). */
class PaystagingIsolationRulesTest {

    private static final String GOOD_URL =
            "jdbc:mysql://db.paystaging.example.com:3306/aurora_app_paystaging?sslMode=REQUIRED";

    /** Fictional, public-looking values only. */
    private static MockEnvironment valid() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("paystaging");
        env.setProperty("spring.datasource.url", GOOD_URL);
        env.setProperty("server.port", "8082");
        env.setProperty("aurora.payment.provider", "YUSEN");
        env.setProperty("aurora.payment.environment", "STAGING");
        env.setProperty("aurora.payment.yusen.pid", "1001");
        env.setProperty("aurora.payment.yusen.key", "fictional-key-for-unit-tests");
        env.setProperty("aurora.payment.yusen.gateway-base-url", "https://pay-gateway.example.com");
        env.setProperty("aurora.payment.yusen.notify-url", "https://api.example.com/api/v1/payment/notify/yusen");
        env.setProperty("aurora.payment.yusen.return-url", "https://api.example.com/api/v1/payment/return/yusen");
        return env;
    }

    private static void guard(MockEnvironment env) {
        new PaystagingEnvironmentGuard().postProcessEnvironment(env, null);
    }

    // T01 ------------------------------------------------------------------------------------
    @Test
    void t01_validPaystagingConfigurationPasses() {
        assertThatCode(() -> guard(valid())).doesNotThrowAnyException();
    }

    @Test
    void t01_realProfileFilesResolveToIsolatedSettingsWithoutSecrets() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("paystaging");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        var base = loader.load("application", new ClassPathResource("application.yml"));
        var profile = loader.load("application-paystaging", new ClassPathResource("application-paystaging.yml"));
        Map<String, Object> deploymentEnv = new HashMap<>();
        deploymentEnv.put("PAYSTAGING_DB_HOST", "db.paystaging.example.com");
        deploymentEnv.put("PAYSTAGING_DB_USERNAME", "paystaging_app");
        deploymentEnv.put("PAYSTAGING_DB_PASSWORD", "fictional-db-password");
        deploymentEnv.put("YUSEN_PID", "1001");
        deploymentEnv.put("YUSEN_KEY", "fictional-key-for-unit-tests");
        deploymentEnv.put("YUSEN_GATEWAY_URL", "https://pay-gateway.example.com");
        deploymentEnv.put("YUSEN_NOTIFY_URL", "https://api.example.com/api/v1/payment/notify/yusen");
        deploymentEnv.put("YUSEN_RETURN_URL", "https://api.example.com/api/v1/payment/return/yusen");
        env.getPropertySources().addFirst(new MapPropertySource("deployment-env", deploymentEnv));
        env.getPropertySources().addLast(profile.get(0));
        base.forEach(ps -> env.getPropertySources().addLast(ps));

        new PaystagingEnvironmentGuard().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.url")).contains("/aurora_app_paystaging?").contains("sslMode=REQUIRED");
        assertThat(env.getProperty("server.port")).isEqualTo("8082");
        assertThat(env.getProperty("aurora.payment.provider")).isEqualTo("YUSEN");
        assertThat(env.getProperty("aurora.payment.environment")).isEqualTo("STAGING");
        assertThat(env.getProperty("aurora.payment.yusen.network-enabled")).isEqualTo("false");
        assertThat(env.getProperty("aurora.payment.reuse-pending-orders")).isEqualTo("false");
        assertThat(env.getProperty("aurora.payment.jobs.enabled")).isEqualTo("true");
        assertThat(env.getProperty("aurora.payment.alert.webhook-url")).isEmpty();

        // No secret defaults in the profile file: credentials are bare placeholders.
        var raw = profile.get(0);
        assertThat(String.valueOf(raw.getProperty("spring.datasource.password"))).isEqualTo("${PAYSTAGING_DB_PASSWORD}");
        assertThat(String.valueOf(raw.getProperty("spring.datasource.username"))).isEqualTo("${PAYSTAGING_DB_USERNAME}");
    }

    @Test
    void t07_profileFilesAloneRefuseToStartWithoutDeploymentValues() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("paystaging");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        env.getPropertySources().addLast(
                loader.load("application-paystaging", new ClassPathResource("application-paystaging.yml")).get(0));
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(ps -> env.getPropertySources().addLast(ps));

        assertThatThrownBy(() -> new PaystagingEnvironmentGuard().postProcessEnvironment(env, null))
                .hasMessageContaining("Refusing to start profile 'paystaging'");
    }

    // T02 / T03 -------------------------------------------------------------------------------
    @Test
    void t02_developmentDatabaseIsRejected() {
        MockEnvironment env = valid();
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3308/aurora_app?useUnicode=true");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("aurora_app belongs to another environment");
    }

    @Test
    void t03_isolatedFakeTestDatabaseIsRejected() {
        MockEnvironment env = valid();
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3307/aurora_app_test");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("aurora_app_test belongs to another environment");

        env.setProperty("spring.datasource.url", "jdbc:mysql://db.example.com/aurora_app_paystaging_copy");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("aurora_app_paystaging_copy");
    }

    @Test
    void t03_unresolvedDatabaseHostIsRejectedWithoutEchoingValues() {
        MockEnvironment env = valid();
        env.setProperty("spring.datasource.url",
                "jdbc:mysql://${PAYSTAGING_DB_HOST}:3306/aurora_app_paystaging");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("unresolved placeholder");
    }

    // T04 / T05 -------------------------------------------------------------------------------
    @Test
    void t04_fakeProviderIsRejected() {
        MockEnvironment env = valid();
        env.setProperty("aurora.payment.provider", "FAKE");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("must be YUSEN (configured: FAKE)");
        env.setProperty("aurora.payment.provider", "NONE");
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("configured: NONE");
    }

    @Test
    void t05_wrongPaymentEnvironmentIsRejected() {
        for (String wrong : new String[] {"TEST", "PRODUCTION"}) {
            MockEnvironment env = valid();
            env.setProperty("aurora.payment.environment", wrong);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("must be STAGING (configured: " + wrong + ")");
        }
        MockEnvironment noEnv = valid();
        noEnv.setProperty("aurora.payment.environment", "");
        assertThatThrownBy(() -> guard(noEnv)).hasMessageContaining("configured: PRODUCTION");
    }

    // T13 / T14 -------------------------------------------------------------------------------
    @Test
    void t13_reservedPortsAreRejected() {
        for (String port : new String[] {"8080", "8081"}) {
            MockEnvironment env = valid();
            env.setProperty("server.port", port);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("server.port " + port);
        }
        MockEnvironment defaulted = valid();
        defaulted.setProperty("server.port", "");
        assertThatThrownBy(() -> guard(defaulted)).hasMessageContaining("server.port 8080");
    }

    @Test
    void t14_incompatibleProfileCombinationsAreRejected() {
        for (String other : new String[] {"s32it", "test", "local"}) {
            MockEnvironment env = valid();
            env.setActiveProfiles("paystaging", other);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("'" + other + "' must not be combined");
        }
    }

    @Test
    void t14_otherProfilesAreUnaffected() {
        MockEnvironment s32it = new MockEnvironment();
        s32it.setActiveProfiles("s32it");
        s32it.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3307/aurora_app_test");
        assertThatCode(() -> guard(s32it)).doesNotThrowAnyException();
    }

    // T06 (configuration stage) / T07 -------------------------------------------------------------
    @Test
    void t06_nonHttpsOrPrivateYusenAddressesAreRejected() {
        String[] keys = {
                "aurora.payment.yusen.gateway-base-url",
                "aurora.payment.yusen.notify-url",
                "aurora.payment.yusen.return-url"};
        for (String key : keys) {
            MockEnvironment http = valid();
            http.setProperty(key, "http://api.example.com/x");
            assertThatThrownBy(() -> guard(http)).hasMessageContaining(key + " must use https");

            for (String privateUrl : new String[] {
                    "https://localhost/x", "https://127.0.0.1/x", "https://192.168.0.107/x",
                    "https://10.1.2.3/x", "https://172.20.0.5/x", "https://api.internal/x"}) {
                MockEnvironment priv = valid();
                priv.setProperty(key, privateUrl);
                assertThatThrownBy(() -> guard(priv)).hasMessageContaining("public host name");
            }
        }
        MockEnvironment webhook = valid();
        webhook.setProperty("aurora.payment.alert.webhook-url", "http://hooks.example.com/x");
        assertThatThrownBy(() -> guard(webhook)).hasMessageContaining("webhook-url must use https");
    }

    @Test
    void t07_missingMerchantSettingsAreRejected() {
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
    void t07_refusalMessagesNeverContainTheMerchantKey() {
        MockEnvironment env = valid();
        env.setProperty("aurora.payment.yusen.notify-url", "http://api.example.com/x");
        assertThatThrownBy(() -> guard(env)).message().doesNotContain("fictional-key-for-unit-tests");
    }

    // T15 connected database (before Flyway) ------------------------------------------------------
    @Test
    void t15_connectedDatabaseMustBePaystaging() throws SQLException {
        assertThatCode(() -> PaystagingIsolationRules.verifyConnectedDatabase(reporting("aurora_app_paystaging")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PaystagingIsolationRules.verifyConnectedDatabase(reporting("aurora_app")))
                .hasMessageContaining("connected database is aurora_app,");
        assertThatThrownBy(() -> PaystagingIsolationRules.verifyConnectedDatabase(reporting("aurora_app_test")))
                .hasMessageContaining("connected database is aurora_app_test");
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("down"));
        assertThatThrownBy(() -> PaystagingIsolationRules.verifyConnectedDatabase(broken))
                .hasMessageContaining("cannot verify");
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
