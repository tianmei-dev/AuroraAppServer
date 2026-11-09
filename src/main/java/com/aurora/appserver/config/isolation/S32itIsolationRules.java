package com.aurora.appserver.config.isolation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The isolated "s32it" environment may only ever run against aurora_app_test with the FAKE
 * payment provider in the TEST payment environment. Enforced in main code so a wrong launch
 * command (e.g. plain bootRun without the fixture config) can never reach another database.
 */
public final class S32itIsolationRules {

    public static final String PROFILE = "s32it";
    public static final String REQUIRED_DATABASE = "aurora_app_test";
    public static final String REQUIRED_PROVIDER = "FAKE";
    public static final String REQUIRED_ENVIRONMENT = "TEST";

    private static final Pattern JDBC_DATABASE =
            Pattern.compile("^jdbc:mysql://[^/?#]+/([^/?#;]+)(?:[?;#].*)?$", Pattern.CASE_INSENSITIVE);

    private S32itIsolationRules() {
    }

    public static boolean isS32it(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(PROFILE));
    }

    /** Configuration-level check; runs before any database connection is opened. */
    public static void verifyConfiguration(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        String database = databaseName(url).orElse(null);
        if (!REQUIRED_DATABASE.equals(database)) {
            throw refusal("spring.datasource.url must target database " + REQUIRED_DATABASE
                    + " (configured: " + (database == null ? "<unparseable or missing>" : database) + ")");
        }
        String provider = environment.getProperty("aurora.payment.provider", "NONE").trim().toUpperCase();
        if (!REQUIRED_PROVIDER.equals(provider)) {
            throw refusal("aurora.payment.provider must be " + REQUIRED_PROVIDER + " (configured: " + provider + ")");
        }
        String paymentEnvironment = environment.getProperty("aurora.payment.environment", "PRODUCTION")
                .trim().toUpperCase();
        if (!REQUIRED_ENVIRONMENT.equals(paymentEnvironment)) {
            throw refusal("aurora.payment.environment must be " + REQUIRED_ENVIRONMENT
                    + " (configured: " + paymentEnvironment + ")");
        }
    }

    /** Connection-level check against the database actually reached. */
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

    private static IllegalStateException refusal(String reason) {
        return new IllegalStateException("Refusing to start profile '" + PROFILE + "': " + reason);
    }
}
