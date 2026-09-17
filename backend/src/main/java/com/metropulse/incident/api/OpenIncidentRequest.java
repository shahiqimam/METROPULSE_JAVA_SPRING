package com.metropulse.incident.api;

import com.metropulse.incident.domain.IncidentSeverity;
import com.metropulse.incident.domain.IncidentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a controller supplies when opening an incident.
 *
 * <p>Status is absent on purpose: a new incident is always OPEN, and letting the caller choose would
 * be the first way around the workflow.
 */
public record OpenIncidentRequest(
        @NotNull IncidentType type,
        @NotNull IncidentSeverity severity,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @Size(max = 50) String vehicleId,
        @Size(max = 40) String routeCode
) {
}
