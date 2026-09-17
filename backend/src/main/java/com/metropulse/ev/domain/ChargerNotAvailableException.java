package com.metropulse.ev.domain;

/**
 * Thrown when a charger cannot be reserved.
 *
 * <p>The expected outcome of two controllers racing for the last charger: one wins, the other is told
 * plainly rather than being handed a constraint violation or, worse, a second session on the same
 * charger.
 */
public class ChargerNotAvailableException extends RuntimeException {

    public ChargerNotAvailableException(String chargerCode, ChargerStatus status) {
        super("Charger " + chargerCode + " is not available: " + status + ".");
    }
}
