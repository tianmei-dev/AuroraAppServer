package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.config.isolation.JdbcUtcSessionRules;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Ensures UTC JDBC URLs force MySQL session time_zone for TIMESTAMP Instant mapping. */
class JdbcUtcSessionRulesTest {

    @Test
    void acceptsUrlWithConnectionTimeZoneAndForceSession() {
        String url = "jdbc:mysql://127.0.0.1:3306/aurora_app_prod"
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=REQUIRED";
        assertThat(JdbcUtcSessionRules.hasUtcConnectionTimeZone(url)).isTrue();
        assertThat(JdbcUtcSessionRules.forcesUtcSession(url)).isTrue();
        assertThatCode(() -> JdbcUtcSessionRules.requireUtcSessionForced(url, "test"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingForceFlagEvenWhenConnectionTimeZonePresent() {
        String url = "jdbc:mysql://127.0.0.1:3306/aurora_app_prod?connectionTimeZone=UTC";
        assertThatThrownBy(() -> JdbcUtcSessionRules.requireUtcSessionForced(url, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forceConnectionTimeZoneToSession=true");
    }

    @Test
    void applicationYmlFilesIncludeForceConnectionTimeZoneToSession() throws Exception {
        assertUrlForcesUtcSession("application.yml");
        assertUrlForcesUtcSession("application-prod.yml");
        assertUrlForcesUtcSession("application-paystaging.yml");
    }

    private static void assertUrlForcesUtcSession(String resource) throws Exception {
        var sources = new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        assertThat(sources).isNotEmpty();
        PropertySource<?> source = sources.get(0);
        Object url = source.getProperty("spring.datasource.url");
        assertThat(url).isNotNull();
        String urlText = String.valueOf(url);
        assertThat(urlText).contains("connectionTimeZone=UTC");
        assertThat(urlText).contains("forceConnectionTimeZoneToSession=true");
        JdbcUtcSessionRules.requireUtcSessionForced(urlText, resource);
    }
}
