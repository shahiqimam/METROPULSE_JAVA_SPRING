package com.metropulse.ev.domain;

/** Thrown when a charger code does not exist. */
public class UnknownChargerException extends RuntimeException {

    public UnknownChargerException(String chargerCode) {
        super("Unknown charger: " + chargerCode);
    }
}
