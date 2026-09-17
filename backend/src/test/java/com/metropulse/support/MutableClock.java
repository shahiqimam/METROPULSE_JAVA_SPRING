package com.metropulse.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move.
 *
 * <p>Rules that depend on how long a condition has held would otherwise need the test to wait in real
 * time, which makes the suite slow and, worse, makes boundary cases impossible to state exactly.
 */
public class MutableClock extends Clock {

    private static final Instant START = Instant.parse("2026-09-17T09:00:00Z");

    private Instant now = START;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void reset() {
        now = START;
    }

    /**
     * Replaces the application clock for tests.
     *
     * <p>Registered on the integration base class, so every integration test shares it; tests that do
     * not touch time are unaffected because the clock only moves when asked.
     */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
