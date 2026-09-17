package com.metropulse.telemetry.read;

import com.metropulse.operations.domain.ConnectivityState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Current operational state of one vehicle, as served to the control centre.
 *
 * <p>{@code routeCode}, {@code routeProgress} and {@code routeDeviationMeters} are null when the
 * vehicle is not assigned to a route, because nothing can be projected onto a route shape then.
 */
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
        Integer batteryPercent,
        String routeCode,
        BigDecimal routeProgress,
        BigDecimal routeDeviationMeters,
        double telemetryAgeSeconds,
        ConnectivityState connectivityState
) {
}
