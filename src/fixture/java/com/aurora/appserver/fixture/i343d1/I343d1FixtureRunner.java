package com.aurora.appserver.fixture.i343d1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CLI entry for I3.4.3-D1. Activated only when {@code i343d1.command} is set under s32it.
 */
@Component
@Profile("s32it")
@ConditionalOnProperty(name = I343d1Constants.PROP_COMMAND)
@Order(0)
public class I343d1FixtureRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(I343d1FixtureRunner.class);

    private final I343d1FixtureService fixtureService;
    private final ConfigurableApplicationContext context;

    public I343d1FixtureRunner(I343d1FixtureService fixtureService, ConfigurableApplicationContext context) {
        this.fixtureService = fixtureService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = context.getEnvironment().getProperty(I343d1Constants.PROP_COMMAND, "").trim();
        int status = 1;
        try {
            switch (command) {
                case "seed" -> {
                    log.info("I3.4.3-D1 command=seed starting");
                    fixtureService.seed();
                    log.info("I3.4.3-D1 command=seed completed");
                }
                case "sessions" -> {
                    java.time.Duration ttl = java.time.Duration.parse(context.getEnvironment()
                            .getProperty(I343d1Constants.PROP_SESSION_TTL, "PT12H"));
                    log.info("I3.4.3-D1 command=sessions starting ttl={}", ttl);
                    fixtureService.refreshSessions(ttl);
                    log.info("I3.4.3-D1 command=sessions completed");
                }
                case "new-pay-accounts" -> {
                    int count = Integer.parseInt(context.getEnvironment()
                            .getProperty(I343d1Constants.PROP_COUNT, "3"));
                    java.time.Duration ttl = java.time.Duration.parse(context.getEnvironment()
                            .getProperty(I343d1Constants.PROP_SESSION_TTL, "PT12H"));
                    log.info("I3.4.3-D1 command=new-pay-accounts starting count={} ttl={}", count, ttl);
                    fixtureService.createPaymentAccounts(count, ttl);
                    log.info("I3.4.3-D1 command=new-pay-accounts completed");
                }
                case "clean" -> {
                    log.info("I3.4.3-D1 command=clean starting");
                    fixtureService.clean();
                    log.info("I3.4.3-D1 command=clean completed");
                }
                default -> throw new IllegalArgumentException(
                        "Unknown i343d1.command='" + command + "' (supported: seed, sessions, new-pay-accounts, clean)");
            }
            status = 0;
        } catch (Exception ex) {
            log.error("I3.4.3-D1 command failed: {}", ex.toString());
            status = 1;
        }
        final int exitCode = status;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
