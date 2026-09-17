package com.metropulse.alert.domain;

/** Thrown when an alert id does not exist. */
public class UnknownAlertException extends RuntimeException {

    public UnknownAlertException(long alertId) {
        super("Unknown alert: " + alertId);
    }
}
