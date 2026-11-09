package com.aurora.appserver.config.isolation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs after all configuration files are loaded and before the application context (and any
 * DataSource) is created. Registered in META-INF/spring.factories.
 */
public class S32itEnvironmentGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (S32itIsolationRules.isS32it(environment)) {
            S32itIsolationRules.verifyConfiguration(environment);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
