package com.metropulse.telemetry.domain;

public class UnknownVehicleException extends RuntimeException {

    public UnknownVehicleException(String vehicleId) {
        super("Unknown vehicle: " + vehicleId);
    }
}
