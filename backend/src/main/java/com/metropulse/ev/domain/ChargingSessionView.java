package com.metropulse.ev.domain;

import java.time.Instant;

/** A charging session, past or present. */
public record ChargingSessionView(
        long id,
        String vehicleId,
        String chargerCode,
        String depotCode,
        ChargingSessionStatus status,
        Instant startedAt,
        Instant endedAt,
        Integer startBatteryPercent,
        Integer endBatteryPercent,
        String startedBy
) {
}
