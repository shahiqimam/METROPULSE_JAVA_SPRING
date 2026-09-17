package com.metropulse.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Makes the current time an injected dependency.
 *
 * <p>Rules that depend on how long a condition has held are otherwise untestable without waiting in
 * real time. With a {@link Clock} bean, a test can move time forward instead.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
