package com.metropulse.alert.domain;

/**
 * Where an alert is in its life.
 *
 * <p>An acknowledged alert is still true; it means a controller has seen it. Only CLOSED means the
 * condition is over, which is why the deduplication index ignores closed alerts alone.
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    CLOSED
}
