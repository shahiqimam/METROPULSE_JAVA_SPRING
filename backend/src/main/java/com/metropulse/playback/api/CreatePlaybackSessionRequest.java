package com.metropulse.playback.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * What to replay.
 *
 * <p>A vehicle, a route, or neither — neither replays the whole network over the window. The window
 * is bounded by the API rather than the caller's patience: an unbounded replay of every vehicle is a
 * way to read the entire telemetry table through a GET.
 */
public record CreatePlaybackSessionRequest(
        @Size(max = 50) String vehicleId,
        @Size(max = 40) String routeCode,
        @NotNull Instant from,
        @NotNull Instant to,
        @DecimalMin("0.1") @DecimalMax("60.0") Double speed
) {

    public double speedOrDefault() {
        return speed == null ? 1.0 : speed;
    }
}
