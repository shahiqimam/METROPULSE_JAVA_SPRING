package com.metropulse.alert.domain;

/**
 * Thrown when a controller acts on an alert that is already closed.
 *
 * <p>Acknowledging something that is over is almost always a sign the screen was stale, so it is
 * refused rather than silently accepted.
 */
public class AlertAlreadyClosedException extends RuntimeException {

    public AlertAlreadyClosedException(long alertId) {
        super("Alert is already closed: " + alertId);
    }
}
