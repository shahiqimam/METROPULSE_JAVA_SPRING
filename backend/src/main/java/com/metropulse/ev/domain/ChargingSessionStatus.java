package com.metropulse.ev.domain;

/** Where a charging session got to. */
public enum ChargingSessionStatus {
    ACTIVE,
    /** Finished normally. */
    COMPLETED,
    /** Stopped before it finished; the charge it delivered still counts. */
    INTERRUPTED
}
