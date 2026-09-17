package com.metropulse.playback.domain;

import java.time.Instant;

/**
 * A replay request that has been accepted.
 *
 * @param frameCount how many observations fall in the window, so a client can size its scrubber
 *                   before fetching anything
 */
public record PlaybackSession(
        long id,
        String vehicleId,
        String routeCode,
        Instant from,
        Instant to,
        double speed,
        int frameCount,
        Instant createdAt,
        String createdBy
) {
}
