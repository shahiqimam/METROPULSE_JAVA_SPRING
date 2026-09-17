package com.metropulse.playback.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One historical observation, as replayed.
 *
 * <p>Read straight from immutable telemetry history. A frame is evidence of where a vehicle was, not
 * a statement about where it is.
 */
public record PlaybackFrame(
        String vehicleId,
        Instant recordedAt,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKph,
        BigDecimal headingDegrees,
        int occupancyEstimate,
        Integer batteryPercent
) {
}
