package com.metropulse.alert.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One condition observed to be true right now.
 *
 * <p>A signal is not an alert. Rules emit signals every evaluation; the alert engine decides which
 * ones have lasted long enough to become alerts, which existing alerts they keep alive, and which
 * alerts should close because their signal has gone.
 *
 * @param fingerprint what makes this the same condition across evaluations. Two signals with the
 *                    same fingerprint are the same problem, so they share one alert.
 */
public record AlertSignal(
        AlertType type,
        String fingerprint,
        AlertSeverity severity,
        String vehicleId,
        String routeCode,
        Map<String, Object> details
) {

    public AlertSignal {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * A signal about one vehicle.
     *
     * <p>The fingerprint is the type plus the vehicle, so a vehicle that is off route has exactly one
     * route-deviation alert however many times it is observed.
     */
    public static AlertSignal forVehicle(
            AlertType type,
            String vehicleId,
            String routeCode,
            Map<String, Object> details
    ) {
        return new AlertSignal(
                type,
                type.name() + "|" + vehicleId,
                type.defaultSeverity(),
                vehicleId,
                routeCode,
                details);
    }

    /**
     * A signal about a pair of vehicles on a route.
     *
     * <p>The fingerprint carries both vehicles: the same follower bunching behind a different leader
     * is a different problem, and swapping which pair is involved should not silently reuse an alert.
     */
    public static AlertSignal forPair(
            AlertType type,
            String routeCode,
            String leaderVehicleId,
            String followerVehicleId,
            Map<String, Object> details
    ) {
        Map<String, Object> enriched = new LinkedHashMap<>(details == null ? Map.of() : details);
        enriched.put("leaderVehicleId", leaderVehicleId);
        enriched.put("followerVehicleId", followerVehicleId);

        return new AlertSignal(
                type,
                String.join("|", type.name(), routeCode, leaderVehicleId, followerVehicleId),
                type.defaultSeverity(),
                followerVehicleId,
                routeCode,
                enriched);
    }
}
