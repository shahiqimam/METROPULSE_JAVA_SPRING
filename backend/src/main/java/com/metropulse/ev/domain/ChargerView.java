package com.metropulse.ev.domain;

import java.math.BigDecimal;

/** A charger and, if it is in use, what is plugged into it. */
public record ChargerView(
        long id,
        String code,
        String depotCode,
        String depotName,
        BigDecimal powerKw,
        ChargerStatus status,
        String occupyingVehicleId
) {
}
