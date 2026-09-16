package com.metropulse.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metropulse")
public record MetroPulseProperties(
        Telemetry telemetry
) {
    public record Telemetry(String ingestKey) {
    }
}
