package com.aurora.appserver.config.isolation;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * s32it only: verify the database actually connected to before Flyway migrates anything, and
 * again at startup in case Flyway is disabled.
 */
@Configuration
@Profile(S32itIsolationRules.PROFILE)
public class S32itIsolationConfig {

    @Bean
    FlywayMigrationStrategy s32itVerifiedFlywayMigration() {
        return flyway -> {
            S32itIsolationRules.verifyConnectedDatabase(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }

    @Bean
    InitializingBean s32itConnectedDatabaseCheck(DataSource dataSource) {
        return () -> S32itIsolationRules.verifyConnectedDatabase(dataSource);
    }
}
