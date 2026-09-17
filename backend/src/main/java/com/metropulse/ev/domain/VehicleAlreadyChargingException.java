package com.metropulse.ev.domain;

/** Thrown when a vehicle that is already plugged in is sent to another charger. */
public class VehicleAlreadyChargingException extends RuntimeException {

    public VehicleAlreadyChargingException(String vehicleId) {
        super("Vehicle " + vehicleId + " already has an active charging session.");
    }
}
