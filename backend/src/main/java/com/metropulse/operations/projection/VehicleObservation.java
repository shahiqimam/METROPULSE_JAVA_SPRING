package com.metropulse.operations.projection;

import java.time.Instant;

/**
 * One telemetry observation, as the operational-state consumer reads it out of an event envelope.
 *
 * <p>Both timestamps are carried: {@code recordedAt} is when the vehicle observed it and
 * {@code receivedAt} is when ingest accepted it. The projection needs both to decide whether the
 * observation is newer than the state it already holds.
 */
public record VehicleObservation(
        String sourceEventId,
        String vehicleId,
        Instant recordedAt,
        Instant receivedAt,
        double latitude,
        double longitude,
        double speedKph,
        double headingDegrees,
        int occupancyEstimate,
        Integer batteryPercent
) {
}
