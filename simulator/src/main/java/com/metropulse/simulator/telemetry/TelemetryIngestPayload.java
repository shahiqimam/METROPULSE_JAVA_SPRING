package com.metropulse.simulator.telemetry;

import java.time.Instant;

public record TelemetryIngestPayload(
        String sourceEventId,
        String vehicleId,
        Instant recordedAt,
        double latitude,
        double longitude,
        double speedKph,
        double headingDegrees,
        int occupancyEstimate,
        Integer batteryPercent
) {
}
