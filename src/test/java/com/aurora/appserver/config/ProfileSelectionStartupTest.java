package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aurora.appserver.AuroraAppServerApplication;
import com.aurora.appserver.support.TestConfigurationExcludeFilter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.io.ClassPathResource;

/**
 * B3-03-A3: real SpringApplication launches. Every database URL points at the unreachable port
 * 127.0.0.1:1, so even a broken guard can never reach a real database. Refusals must happen while
 * the environment is prepared, i.e. before any context, DataSource or Flyway exists.
 */
class ProfileSelectionStartupTest {

    private static final String UNREACHABLE_DEV_DB = "jdbc:mysql://127.0.0.1:1/aurora_app";

    @BeforeAll
    static void noProfileFromTheMachine() {
        assumeTrue(System.getenv("SPRING_PROFILES_ACTIVE") == null, "SPRING_PROFILES_ACTIVE is set on this machine");
        assumeTrue(System.getenv("SPRING_PROFILES_DEFAULT") == null, "SPRING_PROFILES_DEFAULT is set on this machine");
        assumeTrue(System.getProperty("spring.profiles.active") == null, "spring.profiles.active is set");
    }

    private static Throwable launch(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--aurora.payment.jobs.enabled=false",
                "--spring.datasource.hikari.connection-timeout=2000"));
        args.addAll(List.of(extraArgs));
        if (args.stream().noneMatch(a -> a.startsWith("--spring.datasource.url=")
                || a.startsWith("--PAYSTAGING_DB_")
                || a.startsWith("--PROD_DB_"))) {
            args.add("--spring.datasource.url=" + UNREACHABLE_DEV_DB);
        }
        SpringApplication app = new SpringApplication(AuroraAppServerApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.addInitializers(ctx -> ctx.getBeanFactory()
                .registerSingleton("profileTestConfigExclude", new TestConfigurationExcludeFilter()));
        Throwable thrown = catchThrowable(() -> app.run(args.toArray(String[]::new)).close());
        assertThat(thrown).as("startup must fail (every database is unreachable)").isNotNull();
        return thrown;
    }

    private static void assertRefusedBeforeContext(Throwable thrown, String reason) {
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start: " + reason);
        String trace = trace(thrown);
        assertThat(trace).contains("SpringApplication.prepareEnvironment");
        assertThat(trace).doesNotContain("SpringApplication.refreshContext");
    }

    private static String trace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    // A3-S01 ------------------------------------------------------------------------------------
    @Test
    void s01_noProfileIsRefused() {
        assertRefusedBeforeContext(launch(), "no Spring profile is active");
    }

    @Test
    void s01_defaultProfileCannotStandInForAnActiveOne() {
        assertRefusedBeforeContext(launch("--spring.profiles.default=paystaging"), "no Spring profile is active");
        assertRefusedBeforeContext(launch("--spring.profiles.default=local"), "no Spring profile is active");
    }

    // A3-S02 ------------------------------------------------------------------------------------
    @Test
    void s02_combinationsCannotBypassIsolation() {
        assertRefusedBeforeContext(launch("--spring.profiles.active=local,paystaging"), "exactly one profile");
        assertRefusedBeforeContext(launch("--spring.profiles.active=paystaging,local"), "exactly one profile");
        assertRefusedBeforeContext(launch("--spring.profiles.active=paystaging,s32it"), "exactly one profile");
        assertRefusedBeforeContext(launch("--spring.profiles.active=local,s32it"), "exactly one profile");
        assertRefusedBeforeContext(
                launch("--spring.profiles.active=paystaging", "--spring.profiles.include=local"), "exactly one profile");
        assertRefusedBeforeContext(
                launch("--spring.profiles.active=paystaging", "--spring.profiles.group.paystaging=local"),
                "exactly one profile");
    }

    @Test
    void s02_misspelledProfileIsRefused() {
        assertRefusedBeforeContext(launch("--spring.profiles.active=PAYSTAGING"), "unknown profile 'PAYSTAGING'");
        assertRefusedBeforeContext(launch("--spring.profiles.active=production"), "unknown profile 'production'");
        assertRefusedBeforeContext(launch("--spring.profiles.active=PROD"), "unknown profile 'PROD'");
    }

    // A3-S03 ------------------------------------------------------------------------------------
    @Test
    void s03_validPaystagingPassesProfileAndConfigurationGuardsThenStopsAtConnectedDatabaseCheck() {
        Throwable thrown = launch(
                "--spring.profiles.active=paystaging",
                "--PAYSTAGING_DB_HOST=127.0.0.1",
                "--PAYSTAGING_DB_PORT=1",
                "--PAYSTAGING_DB_USERNAME=paystaging_app",
                "--PAYSTAGING_DB_PASSWORD=fictional-db-password",
                "--YUSEN_PID=1001",
                "--YUSEN_KEY=fictional-key-for-unit-tests",
                "--YUSEN_GATEWAY_URL=https://pay-gateway.example.com",
                "--YUSEN_NOTIFY_URL=https://api.example.com/api/v1/payment/notify/yusen",
                "--YUSEN_RETURN_URL=https://api.example.com/api/v1/payment/return/yusen");
        String trace = trace(thrown);
        assertThat(trace).doesNotContain("Refusing to start:");
        assertThat(trace).contains("SpringApplication.refreshContext");
        assertThat(trace).contains("Refusing to start profile 'paystaging': cannot verify connected database");
        assertThat(trace).doesNotContain("fictional-key-for-unit-tests").doesNotContain("fictional-db-password");
    }

    @Test
    void s03_paystagingWithDevelopmentDatabaseIsStillRefusedByItsOwnGuard() {
        Throwable thrown = launch("--spring.profiles.active=paystaging", "--spring.datasource.url=" + UNREACHABLE_DEV_DB);
        assertThat(thrown).hasMessageContaining("Refusing to start profile 'paystaging': database aurora_app belongs to another environment");
    }

    @Test
    void s03_validProdPassesProfileAndConfigurationGuardsThenStopsAtConnectedDatabaseCheck() {
        Throwable thrown = launch(
                "--spring.profiles.active=prod",
                "--PROD_DB_HOST=127.0.0.1",
                "--PROD_DB_PORT=1",
                "--PROD_DB_USERNAME=prod_app",
                "--PROD_DB_PASSWORD=fictional-db-password",
                "--YUSEN_PID=1001",
                "--YUSEN_KEY=fictional-key-for-unit-tests",
                "--YUSEN_GATEWAY_URL=https://ivo.hzlugun.top",
                "--YUSEN_NOTIFY_URL=https://aurora-api.appforfun.top/api/v1/payment/notify/yusen",
                "--YUSEN_RETURN_URL=https://aurora-api.appforfun.top/api/v1/payment/return/yusen");
        String trace = trace(thrown);
        assertThat(trace).doesNotContain("Refusing to start:");
        assertThat(trace).contains("SpringApplication.refreshContext");
        assertThat(trace).contains("Refusing to start profile 'prod': cannot verify connected database");
        assertThat(trace).doesNotContain("fictional-key-for-unit-tests").doesNotContain("fictional-db-password");
    }

    @Test
    void s03_prodWithPaystagingDatabaseIsRefusedByItsOwnGuard() {
        Throwable thrown = launch(
                "--spring.profiles.active=prod",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/aurora_app_paystaging");
        assertThat(thrown).hasMessageContaining(
                "Refusing to start profile 'prod': database aurora_app_paystaging belongs to another environment");
    }

    // A3-S04 ------------------------------------------------------------------------------------
    @Test
    void s04_localProfilePassesWhenTheDeveloperConfigurationExists() {
        assumeTrue(new ClassPathResource("application-local.yml").exists(),
                "developer-only application-local.yml is not present on this machine");
        String trace = trace(launch("--spring.profiles.active=local"));
        assertThat(trace).doesNotContain("Refusing to start");
        assertThat(trace).contains("SpringApplication.refreshContext");
    }

    @Test
    void s04_s32itKeepsItsOwnDatabaseGuard() {
        Throwable s32it = launch("--spring.profiles.active=s32it");
        assertThat(s32it).hasMessageContaining("Refusing to start profile 's32it'")
                .hasMessageContaining("configured: aurora_app");
    }
}
