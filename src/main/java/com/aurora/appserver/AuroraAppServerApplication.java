package com.aurora.appserver;

import com.aurora.appserver.auth.config.AuroraAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * API authentication is Bearer-token based ({@code SecurityConfig} +
 * {@code BearerTokenAuthenticationFilter}). Exclude Boot's default in-memory user so it never
 * generates or logs a development security password.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AuroraAuthProperties.class)
public class AuroraAppServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuroraAppServerApplication.class, args);
    }
}
