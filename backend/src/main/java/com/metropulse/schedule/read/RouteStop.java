package com.metropulse.schedule.read;

import java.math.BigDecimal;

public record RouteStop(
        String stopCode,
        String stopName,
        int stopSequence,
        int plannedArrivalSeconds,
        int plannedDepartureSeconds,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
