package com.metropulse.operations.domain;

/**
 * Telemetry freshness of a single vehicle.
 *
 * <p>These are MetroPulse project thresholds, not a transit-industry standard. They describe how
 * recently a vehicle reported, and say nothing about whether the platform itself is healthy: a fleet
 * that is entirely OFFLINE usually means ingestion is broken, not that every bus stopped.
 */
public enum ConnectivityState {

    /** Reported within the last {@value #ONLINE_MAX_AGE_SECONDS} seconds. */
    ONLINE,

    /** Last report is older than ONLINE allows but still within {@value #STALE_MAX_AGE_SECONDS} seconds. */
    STALE,

    /** No report for more than {@value #STALE_MAX_AGE_SECONDS} seconds. */
    OFFLINE;

    public static final double ONLINE_MAX_AGE_SECONDS = 15.0;
    public static final double STALE_MAX_AGE_SECONDS = 60.0;

    /**
     * Classifies a vehicle by the age of its most recent telemetry.
     *
     * @param ageSeconds seconds between the observation's recorded time and now; a negative age means
     *                   the observation is timestamped in the future, which is treated as fresh
     * @return the connectivity state for that age
     */
    public static ConnectivityState classify(double ageSeconds) {
        if (ageSeconds <= ONLINE_MAX_AGE_SECONDS) {
            return ONLINE;
        }
        if (ageSeconds <= STALE_MAX_AGE_SECONDS) {
            return STALE;
        }
        return OFFLINE;
    }
}
