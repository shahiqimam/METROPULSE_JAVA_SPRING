package com.metropulse.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metropulse")
public record MetroPulseProperties(
        Telemetry telemetry,
        Outbox outbox
) {
    public record Telemetry(String ingestKey) {
    }

    public record Outbox(
            boolean enabled,
            String topic,
            int batchSize,
            long publishDelayMs
    ) {
    }
}
