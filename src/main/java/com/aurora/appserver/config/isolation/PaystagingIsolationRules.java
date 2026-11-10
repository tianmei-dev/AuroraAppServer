package com.aurora.appserver.config.isolation;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * "paystaging" is the isolated REAL Yusen integration environment. Whatever the configuration
 * says, it may only run against aurora_app_paystaging, with the YUSEN provider in the STAGING
 * payment environment, on a port other than 8080/8081, with complete https merchant settings.
 * Enforced in main code before any database connection is opened.
 */
public final class PaystagingIsolationRules {

    public static final String PROFILE = "paystaging";
    public static final String REQUIRED_DATABASE = "aurora_app_paystaging";
    public static final String REQUIRED_PROVIDER = "YUSEN";
    public static final String REQUIRED_ENVIRONMENT = "STAGING";
    static final Set<String> FORBIDDEN_DATABASES = Set.of("aurora_app", "aurora_app_test");
    static final Set<Integer> FORBIDDEN_PORTS = Set.of(8080, 8081);
    static final List<String> INCOMPATIBLE_PROFILES = List.of("s32it", "test", "local");

    private static final Pattern JDBC_DATABASE =
            Pattern.compile("^jdbc:mysql://[^/?#]+/([^/?#;]+)(?:[?;#].*)?$", Pattern.CASE_INSENSITIVE);

    private PaystagingIsolationRules() {
    }

    public static boolean isPaystaging(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(PROFILE));
    }

    /** Configuration-level check; runs before the application context or any DataSource exists. */
    public static void verifyConfiguration(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        for (String incompatible : INCOMPATIBLE_PROFILES) {
            if (active.contains(incompatible)) {
                throw refusal("profile '" + incompatible + "' must not be combined with '" + PROFILE + "'");
            }
        }

        String url = property(environment, "spring.datasource.url");
        String database = databaseName(url).orElse(null);
        if (database != null && FORBIDDEN_DATABASES.contains(database)) {
            throw refusal("database " + database + " belongs to another environment");
        }
        if (!REQUIRED_DATABASE.equals(database)) {
            throw refusal("spring.datasource.url must target database " + REQUIRED_DATABASE
                    + " (configured: " + (database == null ? "<unparseable or missing>" : database) + ")");
        }

        String provider = upper(property(environment, "aurora.payment.provider"), "NONE");
        if (!REQUIRED_PROVIDER.equals(provider)) {
            throw refusal("aurora.payment.provider must be " + REQUIRED_PROVIDER + " (configured: " + provider + ")");
        }
        String paymentEnvironment = upper(property(environment, "aurora.payment.environment"), "PRODUCTION");
        if (!REQUIRED_ENVIRONMENT.equals(paymentEnvironment)) {
            throw refusal("aurora.payment.environment must be " + REQUIRED_ENVIRONMENT
                    + " (configured: " + paymentEnvironment + ")");
        }

        int port = port(property(environment, "server.port"));
        if (FORBIDDEN_PORTS.contains(port)) {
            throw refusal("server.port " + port + " is reserved for another environment");
        }

        requireText(environment, "aurora.payment.yusen.pid", "YUSEN_PID");
        requireText(environment, "aurora.payment.yusen.key", "YUSEN_KEY");
        requirePublicHttps(environment, "aurora.payment.yusen.gateway-base-url", "YUSEN_GATEWAY_URL");
        requirePublicHttps(environment, "aurora.payment.yusen.notify-url", "YUSEN_NOTIFY_URL");
        requirePublicHttps(environment, "aurora.payment.yusen.return-url", "YUSEN_RETURN_URL");

        String webhook = property(environment, "aurora.payment.alert.webhook-url");
        if (webhook != null && !webhook.isBlank() && !isHttps(webhook)) {
            throw refusal("aurora.payment.alert.webhook-url must use https when set (PAYMENT_ALERT_WEBHOOK_URL)");
        }
    }

    /** Connection-level check against the database actually reached (before Flyway). */
    public static void verifyConnectedDatabase(DataSource dataSource) {
        String actual;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            actual = rs.next() ? rs.getString(1) : null;
        } catch (Exception ex) {
            throw refusal("cannot verify connected database (" + ex.getClass().getSimpleName() + ")");
        }
        if (!REQUIRED_DATABASE.equals(actual)) {
            throw refusal("connected database is " + actual + ", expected " + REQUIRED_DATABASE);
        }
    }

    static Optional<String> databaseName(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        Matcher m = JDBC_DATABASE.matcher(jdbcUrl.trim());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static String property(Environment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException unresolved) {
            // e.g. "${PAYSTAGING_DB_HOST}" without a value; never echo the raw value.
            throw refusal(key + " has an unresolved placeholder (missing environment variable)");
        }
    }

    private static void requireText(Environment environment, String key, String envName) {
        String value = property(environment, key);
        if (value == null || value.isBlank()) {
            throw refusal(key + " is required (" + envName + ")");
        }
    }

    private static void requirePublicHttps(Environment environment, String key, String envName) {
        String value = property(environment, key);
        if (value == null || value.isBlank()) {
            throw refusal(key + " is required (" + envName + ")");
        }
        if (!isHttps(value)) {
            throw refusal(key + " must use https (" + envName + ")");
        }
        String host;
        try {
            host = URI.create(value.trim()).getHost();
        } catch (IllegalArgumentException ex) {
            throw refusal(key + " is not a valid URL (" + envName + ")");
        }
        if (host == null || isLocalOrPrivateHost(host)) {
            throw refusal(key + " must use a public host name (" + envName + ")");
        }
    }

    static boolean isHttps(String url) {
        return url != null && url.trim().toLowerCase().startsWith("https://");
    }

    static boolean isLocalOrPrivateHost(String host) {
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal")
                || h.endsWith(".test") || h.startsWith("127.") || h.equals("0.0.0.0") || h.startsWith("10.")
                || h.startsWith("192.168.") || h.startsWith("169.254.") || h.startsWith("[")) {
            return true;
        }
        Matcher m = Pattern.compile("^172\\.(\\d{1,3})\\.").matcher(h);
        return m.find() && Integer.parseInt(m.group(1)) >= 16 && Integer.parseInt(m.group(1)) <= 31;
    }

    private static int port(String value) {
        if (value == null || value.isBlank()) {
            return 8080;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw refusal("server.port is not a number");
        }
    }

    private static String upper(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase();
    }

    private static IllegalStateException refusal(String reason) {
        return new IllegalStateException("Refusing to start profile '" + PROFILE + "': " + reason);
    }
}
