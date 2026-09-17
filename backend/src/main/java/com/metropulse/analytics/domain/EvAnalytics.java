package com.metropulse.analytics.domain;

/** Charger availability and what charging has achieved. */
public record EvAnalytics(
        int chargersTotal,
        int chargersAvailable,
        int chargersOccupied,
        int chargersOutOfService,
        int sessionsInWindow,
        int sessionsActive,
        double averageSessionSeconds,
        double averageBatteryPercentGained,
        double averageFleetBatteryPercent,
        int lowBatteryVehicles
) {
}
