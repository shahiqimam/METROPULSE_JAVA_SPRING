package com.metropulse.telemetry.read;

import java.math.BigDecimal;
import java.time.Instant;

public record LatestVehicleTelemetry(
        String vehicleId,
        String vehicleType,
        String propulsionType,
        int capacity,
        String status,
        String sourceEventId,
        Instant recordedAt,
        Instant receivedAt,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKph,
        BigDecimal headingDegrees,
        int occupancyEstimate,
        Integer batteryPercent
) {
}
