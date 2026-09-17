package com.metropulse.ev.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What a controller supplies to plug a vehicle in. */
public record StartChargingSessionRequest(
        @NotBlank @Size(max = 40) String chargerCode,
        @NotBlank @Size(max = 50) String vehicleId
) {
}
