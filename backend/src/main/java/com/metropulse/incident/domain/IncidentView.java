package com.metropulse.incident.domain;

import java.time.Instant;
import java.util.List;

/** An incident and its history, as the control centre reads it. */
public record IncidentView(
        long id,
        String incidentNumber,
        IncidentType type,
        IncidentSeverity severity,
        IncidentStatus status,
        String title,
        String description,
        String vehicleId,
        String routeCode,
        String openedBy,
        String assignedController,
        Instant startedAt,
        Instant acknowledgedAt,
        Instant mitigatingAt,
        Instant resolvedAt,
        Instant cancelledAt,
        List<IncidentTimelineEntry> timeline
) {
}
