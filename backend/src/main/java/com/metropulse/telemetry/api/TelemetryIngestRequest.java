package com.metropulse.telemetry.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record TelemetryIngestRequest(
        @NotBlank String sourceEventId,
        @NotBlank String vehicleId,
        @NotNull Instant recordedAt,
        @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
        @DecimalMin("0.0") @DecimalMax("160.0") double speedKph,
        @DecimalMin("0.0") @DecimalMax("360.0") double headingDegrees,
        @Min(0) @Max(250) int occupancyEstimate,
        @Min(0) @Max(100) Integer batteryPercent
) {
}
