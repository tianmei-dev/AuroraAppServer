package com.aurora.appserver.fixture.i343d1;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Active only while the I3.4.3-D1 CLI command property is set under s32it.
 * Standing bootRun-s32it (no i343d1.command) keeps system UTC Clock.
 */
@Configuration
@Profile("s32it")
@ConditionalOnProperty(name = I343d1Constants.PROP_COMMAND)
public class I343d1FixtureConfig {

    @Bean
    @Primary
    Clock i343d1FixtureClock(I343d1MutableClock mutableClock) {
        return mutableClock;
    }

    @Bean
    I343d1MutableClock i343d1MutableClock() {
        return new I343d1MutableClock();
    }
}
