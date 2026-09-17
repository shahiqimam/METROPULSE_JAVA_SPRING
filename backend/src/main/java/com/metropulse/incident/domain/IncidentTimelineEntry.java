package com.metropulse.incident.domain;

import java.time.Instant;

/**
 * One entry in an incident's history.
 *
 * <p>Append-only: a transition that happened cannot be edited away, and a note stands as written.
 */
public record IncidentTimelineEntry(
        long id,
        String entryType,
        IncidentStatus fromStatus,
        IncidentStatus toStatus,
        String note,
        String actor,
        Instant recordedAt
) {
}
