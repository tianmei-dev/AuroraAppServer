package com.aurora.appserver.config.isolation;

/**
 * JDBC URLs that use UTC for Instant ↔ MySQL TIMESTAMP must force the session time_zone.
 * {@code connectionTimeZone=UTC} alone does not always issue {@code SET time_zone}; without
 * {@code forceConnectionTimeZoneToSession=true}, a server default of +08:00 shifts TIMESTAMP(3)
 * by eight hours on read/write.
 */
public final class JdbcUtcSessionRules {

    private JdbcUtcSessionRules() {
    }

    public static boolean hasUtcConnectionTimeZone(String jdbcUrl) {
        return containsParam(jdbcUrl, "connectionTimeZone", "UTC")
                || containsParam(jdbcUrl, "serverTimezone", "UTC");
    }

    public static boolean forcesUtcSession(String jdbcUrl) {
        return containsParam(jdbcUrl, "forceConnectionTimeZoneToSession", "true");
    }

    public static void requireUtcSessionForced(String jdbcUrl, String envHint) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("JDBC URL is missing (" + envHint + ")");
        }
        if (!hasUtcConnectionTimeZone(jdbcUrl)) {
            throw new IllegalStateException(
                    "JDBC URL must set connectionTimeZone=UTC (or serverTimezone=UTC) (" + envHint + ")");
        }
        if (!forcesUtcSession(jdbcUrl)) {
            throw new IllegalStateException(
                    "JDBC URL must set forceConnectionTimeZoneToSession=true so MySQL session "
                            + "time_zone is UTC for TIMESTAMP Instant mapping (" + envHint + "). "
                            + "Do not override spring.datasource.url / SPRING_DATASOURCE_URL without this flag.");
        }
    }

    private static boolean containsParam(String jdbcUrl, String name, String expectedValue) {
        String lower = jdbcUrl.toLowerCase();
        String key = name.toLowerCase() + "=";
        int idx = lower.indexOf(key);
        while (idx >= 0) {
            int start = idx + key.length();
            int end = start;
            while (end < lower.length()) {
                char c = lower.charAt(end);
                if (c == '&' || c == '#' || c == ';') {
                    break;
                }
                end++;
            }
            String value = lower.substring(start, end).trim();
            if (value.equals(expectedValue.toLowerCase())) {
                return true;
            }
            idx = lower.indexOf(key, start);
        }
        return false;
    }
}
