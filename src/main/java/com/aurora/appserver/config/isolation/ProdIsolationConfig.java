package com.aurora.appserver.config.isolation;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * prod only: verify the database actually connected to before Flyway migrates anything,
 * and again at startup in case Flyway is disabled.
 */
@Configuration
@Profile(ProdIsolationRules.PROFILE)
public class ProdIsolationConfig {

    @Bean
    FlywayMigrationStrategy prodVerifiedFlywayMigration() {
        return flyway -> {
            ProdIsolationRules.verifyConnectedDatabase(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }

    @Bean
    InitializingBean prodConnectedDatabaseCheck(DataSource dataSource) {
        return () -> ProdIsolationRules.verifyConnectedDatabase(dataSource);
    }
}
