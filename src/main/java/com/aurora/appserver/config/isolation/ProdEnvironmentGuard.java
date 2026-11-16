package com.aurora.appserver.config.isolation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs after all configuration sources are loaded and before the application context or any
 * DataSource is created. Registered in META-INF/spring.factories.
 */
public class ProdEnvironmentGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (ProdIsolationRules.isProd(environment)) {
            ProdIsolationRules.verifyConfiguration(environment);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
