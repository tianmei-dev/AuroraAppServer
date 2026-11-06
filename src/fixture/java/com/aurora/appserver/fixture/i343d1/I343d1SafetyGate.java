package com.aurora.appserver.fixture.i343d1;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Hard stops the fixture unless profile is s32it and JDBC catalog is aurora_app_test.
 */
@Component
public class I343d1SafetyGate {

    private static final Logger log = LoggerFactory.getLogger(I343d1SafetyGate.class);

    private final Environment environment;
    private final DataSource dataSource;

    public I343d1SafetyGate(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    public void assertSafeToRun() {
        if (!environment.acceptsProfiles(Profiles.of("s32it"))) {
            throw new IllegalStateException(
                    "I3.4.3-D1 fixture refused: active profiles must include s32it (got "
                            + String.join(",", environment.getActiveProfiles()) + ")");
        }

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String url = meta.getURL() == null ? "" : meta.getURL().toLowerCase();

            if (url.contains("3308") || url.contains("/aurora_app?") || url.endsWith("/aurora_app")) {
                throw new IllegalStateException(
                        "I3.4.3-D1 fixture refused: appears to target aurora_app/3308");
            }
            if (!url.contains("3307") && !url.contains("aurora_app_test")) {
                throw new IllegalStateException(
                        "I3.4.3-D1 fixture refused: JDBC URL must target test DB (3307/aurora_app_test), url="
                                + redactUrl(meta.getURL()));
            }

            String database;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
                if (!rs.next()) {
                    throw new IllegalStateException("I3.4.3-D1 fixture refused: SELECT DATABASE() empty");
                }
                database = rs.getString(1);
            }
            if (!"aurora_app_test".equalsIgnoreCase(database)) {
                throw new IllegalStateException(
                        "I3.4.3-D1 fixture refused: DATABASE() must be aurora_app_test, got=" + database);
            }

            log.info("I3.4.3-D1 safety gate OK profile=s32it catalog=aurora_app_test");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("I3.4.3-D1 fixture refused: cannot verify JDBC target", ex);
        }
    }

    private static String redactUrl(String url) {
        if (url == null) {
            return "(null)";
        }
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }
}
