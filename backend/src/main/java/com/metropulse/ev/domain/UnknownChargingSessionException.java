package com.metropulse.ev.domain;

/** Thrown when a charging session id does not exist. */
public class UnknownChargingSessionException extends RuntimeException {

    public UnknownChargingSessionException(long sessionId) {
        super("Unknown charging session: " + sessionId);
    }
}
