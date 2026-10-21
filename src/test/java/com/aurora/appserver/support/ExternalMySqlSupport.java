package com.aurora.appserver.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points integration tests at an isolated MySQL instance.
 * Default: Docker container published on localhost:3307 (see docker-compose.test.yml).
 * Override with TEST_DB_* environment variables when needed.
 * Never targets the local development aurora_app database by default.
 */
public final class ExternalMySqlSupport {

    private ExternalMySqlSupport() {
    }

    public static void registerDatasource(DynamicPropertyRegistry registry) {
        String host = env("TEST_DB_HOST", "127.0.0.1");
        String port = env("TEST_DB_PORT", "3307");
        String db = env("TEST_DB_NAME", "aurora_app_test");
        String user = env("TEST_DB_USERNAME", "test");
        String password = env("TEST_DB_PASSWORD", "test");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false";

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
