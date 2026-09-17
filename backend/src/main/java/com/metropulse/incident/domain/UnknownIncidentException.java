package com.metropulse.incident.domain;

/** Thrown when an incident id does not exist. */
public class UnknownIncidentException extends RuntimeException {

    public UnknownIncidentException(long incidentId) {
        super("Unknown incident: " + incidentId);
    }
}
