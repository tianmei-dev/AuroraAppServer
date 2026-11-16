package com.aurora.appserver.config.isolation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs after config data (profile includes and groups are resolved by then) and before the
 * per-profile guards, the application context, any DataSource or Flyway. Registered in
 * META-INF/spring.factories.
 */
public class ProfileSelectionGuard implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ProfileSelectionRules.verify(environment);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
