package com.metropulse.incident.api;

import jakarta.validation.constraints.Size;

/** An optional note recorded with a workflow action. */
public record IncidentActionRequest(@Size(max = 4000) String note) {
}
