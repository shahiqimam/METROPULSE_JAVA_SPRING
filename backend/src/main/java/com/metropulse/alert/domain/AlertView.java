package com.metropulse.alert.domain;

import java.time.Instant;
import java.util.Map;

/** An alert as the control centre reads it. */
public record AlertView(
        long id,
        AlertType type,
        String fingerprint,
        AlertSeverity severity,
        AlertStatus status,
        String vehicleId,
        String routeCode,
        Instant openedAt,
        Instant lastObservedAt,
        Instant recoveringSince,
        Instant acknowledgedAt,
        String acknowledgedBy,
        Instant closedAt,
        AlertCloseReason closeReason,
        Map<String, Object> details
) {
}
