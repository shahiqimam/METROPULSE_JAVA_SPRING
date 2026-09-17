package com.metropulse.alert.domain;

import java.time.Duration;

/**
 * The conditions MetroPulse raises alerts for.
 *
 * <p>Each type carries its own persistence window: how long the condition must hold before it is
 * worth a controller's attention, and how long it must be absent before the alert closes. Those two
 * windows are what stop a fluctuating measurement from opening and closing an alert repeatedly.
 *
 * <p>All thresholds here are MetroPulse project values, not transit-industry standards.
 */
public enum AlertType {

    /** Telemetry has been absent long enough that the vehicle's position is no longer trustworthy. */
    TELEMETRY_OFFLINE(AlertSeverity.MAJOR, Duration.ZERO, Duration.ofSeconds(15)),

    /** The vehicle is further from its route shape than the deviation rule allows. */
    ROUTE_DEVIATION(AlertSeverity.MAJOR, Duration.ofSeconds(60), Duration.ofSeconds(60)),

    /** A vehicle has closed on the one ahead of it. */
    BUNCHING(AlertSeverity.MINOR, Duration.ZERO, Duration.ofSeconds(60)),

    /** A gap has opened in the service. */
    EXCESSIVE_GAP(AlertSeverity.MINOR, Duration.ZERO, Duration.ofSeconds(60)),

    /** An electric vehicle is low enough on charge to need planning around. */
    LOW_BATTERY(AlertSeverity.MAJOR, Duration.ofSeconds(60), Duration.ofSeconds(120)),

    /** The vehicle is carrying at or beyond its rated capacity. */
    OVER_CAPACITY(AlertSeverity.MINOR, Duration.ofSeconds(60), Duration.ofSeconds(60));

    private final AlertSeverity defaultSeverity;
    private final Duration persistenceWindow;
    private final Duration recoveryWindow;

    AlertType(AlertSeverity defaultSeverity, Duration persistenceWindow, Duration recoveryWindow) {
        this.defaultSeverity = defaultSeverity;
        this.persistenceWindow = persistenceWindow;
        this.recoveryWindow = recoveryWindow;
    }

    public AlertSeverity defaultSeverity() {
        return defaultSeverity;
    }

    /**
     * How long the condition must hold before an alert opens.
     *
     * <p>Zero for conditions that already carry their own persistence: a vehicle is only OFFLINE
     * after 60 seconds of silence, and a headway condition is only confirmed after 90 seconds, so
     * requiring a further window would double-count the wait.
     */
    public Duration persistenceWindow() {
        return persistenceWindow;
    }

    /** How long the condition must be absent before the alert closes. */
    public Duration recoveryWindow() {
        return recoveryWindow;
    }
}
