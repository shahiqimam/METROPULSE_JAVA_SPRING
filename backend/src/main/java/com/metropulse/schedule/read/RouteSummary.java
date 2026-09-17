package com.metropulse.schedule.read;

public record RouteSummary(
        String code,
        String shortName,
        String longName,
        String agencyName,
        boolean active,
        int stopCount,
        int tripCount,
        int routePointCount
) {
}
