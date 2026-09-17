package com.metropulse.analytics.domain;

/** Incident volume and how long they took, measured from the incidents' own timestamps. */
public record IncidentAnalytics(
        int total,
        int live,
        int resolved,
        int cancelled,
        int critical,
        double averageSecondsToAcknowledge,
        double averageSecondsToResolve
) {
}
