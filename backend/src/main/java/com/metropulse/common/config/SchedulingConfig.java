package com.metropulse.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the background schedulers on.
 *
 * <p>Separated from the application class so it can be switched off. Integration tests drive the
 * outbox publisher, the headway evaluator and the alert engine deliberately, one call at a time, and
 * a scheduler running the same work in the background changes state underneath the assertions. That
 * produces tests that fail occasionally for reasons unrelated to what they are testing, which is
 * worse than no test.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "metropulse.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
