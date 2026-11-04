package com.aurora.appserver.fixture.i343d1;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Process-local clock for the I3.4.3-D1 CLI only. Not used by the standing 8081 process.
 */
public final class I343d1MutableClock extends Clock {

    private volatile Instant instant = Instant.now();
    private final ZoneId zone = ZoneOffset.UTC;

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("I343d1MutableClock is UTC-only");
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void resetToSystemUtc() {
        this.instant = Instant.now();
    }
}
