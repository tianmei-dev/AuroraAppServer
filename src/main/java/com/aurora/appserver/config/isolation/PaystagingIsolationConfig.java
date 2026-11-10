package com.aurora.appserver.config.isolation;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * paystaging only: verify the database actually connected to before Flyway migrates anything,
 * and again at startup in case Flyway is disabled.
 */
@Configuration
@Profile(PaystagingIsolationRules.PROFILE)
public class PaystagingIsolationConfig {

    @Bean
    FlywayMigrationStrategy paystagingVerifiedFlywayMigration() {
        return flyway -> {
            PaystagingIsolationRules.verifyConnectedDatabase(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }

    @Bean
    InitializingBean paystagingConnectedDatabaseCheck(DataSource dataSource) {
        return () -> PaystagingIsolationRules.verifyConnectedDatabase(dataSource);
    }
}
