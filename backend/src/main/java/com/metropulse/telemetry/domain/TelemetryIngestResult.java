package com.metropulse.telemetry.domain;

public record TelemetryIngestResult(
        String sourceEventId,
        TelemetryIngestStatus status
) {
}
