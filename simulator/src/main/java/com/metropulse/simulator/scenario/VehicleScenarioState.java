package com.metropulse.simulator.scenario;

public record VehicleScenarioState(
        int routePointIndex,
        double speedKph,
        double headingDegrees,
        int occupancyEstimate,
        int batteryPercent
) {
}
