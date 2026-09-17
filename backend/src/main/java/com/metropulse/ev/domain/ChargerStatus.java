package com.metropulse.ev.domain;

/** Whether a charger can be used. */
public enum ChargerStatus {
    /** Free and working. */
    AVAILABLE,
    /** In use by an active session. */
    OCCUPIED,
    /** Not responding. */
    OFFLINE,
    /** Deliberately out of service. */
    MAINTENANCE
}
