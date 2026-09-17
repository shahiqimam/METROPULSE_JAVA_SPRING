package com.metropulse.operations.projection;

/**
 * Thrown when an event names a vehicle that does not exist.
 *
 * <p>Retrying cannot fix this, so the consumer treats it as a poison message and routes it to the
 * dead-letter topic rather than blocking the partition.
 */
public class UnknownVehicleInEventException extends RuntimeException {

    public UnknownVehicleInEventException(String vehicleId) {
        super("Event references unknown vehicle: " + vehicleId);
    }
}
