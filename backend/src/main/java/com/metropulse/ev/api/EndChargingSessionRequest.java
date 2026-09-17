package com.metropulse.ev.api;

import com.metropulse.ev.domain.ChargingSessionStatus;

/**
 * How a session ended.
 *
 * <p>Defaults to COMPLETED, so the ordinary case needs no body at all; INTERRUPTED has to be stated,
 * because "this charge was cut short" is a claim about what happened.
 */
public record EndChargingSessionRequest(ChargingSessionStatus status) {

    public ChargingSessionStatus statusOrCompleted() {
        return status == null ? ChargingSessionStatus.COMPLETED : status;
    }
}
