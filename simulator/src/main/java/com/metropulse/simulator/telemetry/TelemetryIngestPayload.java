package com.metropulse.simulator.telemetry;

import java.time.Instant;

/**
 * One observation, as the vehicle reports it.
 *
 * @param tripId the trip the vehicle is running, reported rather than inferred: two trips on the same
 *               route pass the same point, so guessing from position and time would introduce error
 *               into the very measurement schedule adherence depends on
 */
public record TelemetryIngestPayload(
        String sourceEventId,
        String vehicleId,
        String tripId,
        Instant recordedAt,
        double latitude,
        double longitude,
        double speedKph,
        double headingDegrees,
        int occupancyEstimate,
        Integer batteryPercent
) {
}
