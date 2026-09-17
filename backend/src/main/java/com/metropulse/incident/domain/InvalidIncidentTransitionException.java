package com.metropulse.incident.domain;

/**
 * Thrown when a workflow transition is not allowed from the incident's current state.
 *
 * <p>Carries both states because "you cannot do that" without saying from where is not actionable.
 */
public class InvalidIncidentTransitionException extends RuntimeException {

    public InvalidIncidentTransitionException(String incidentNumber, IncidentStatus from, IncidentStatus to) {
        super("Incident " + incidentNumber + " cannot move from " + from + " to " + to + ".");
    }
}
