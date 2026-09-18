package com.metropulse.alert.application;

import com.metropulse.alert.domain.AlertSignal;
import com.metropulse.alert.domain.AlertType;
import com.metropulse.operations.domain.ConnectivityState;
import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayConditionType;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the current picture into the set of conditions that are true right now.
 *
 * <p>Pure: given state in, signals out. No database, no clock, no alert history beyond the set of
 * fingerprints that already have a live alert, which is needed for hysteresis.
 *
 * <p><strong>Hysteresis.</strong> Route deviation opens at 100 m but does not clear until the vehicle
 * is back within 60 m. Without that band, a vehicle sitting at 100 m would flap: one reading at 101 m
 * opens an alert, the next at 99 m closes it, and the controller learns to ignore the whole category.
 * The same idea applies to battery and capacity.
 *
 * <p>All thresholds are MetroPulse project values, not transit-industry standards.
 */
public final class AlertRules {

    public static final double ROUTE_DEVIATION_OPEN_METERS = 100.0;
    public static final double ROUTE_DEVIATION_CLEAR_METERS = 60.0;

    public static final int LOW_BATTERY_OPEN_PERCENT = 20;
    public static final int LOW_BATTERY_CLEAR_PERCENT = 30;

    public static final double OVER_CAPACITY_OPEN_RATIO = 1.0;
    public static final double OVER_CAPACITY_CLEAR_RATIO = 0.9;

    /**
     * Schedule tolerances, in seconds.
     *
     * <p>Asymmetric on purpose. Running late is normal in traffic and only worth saying when it is
     * substantial; running early is a choice the driver made, and a passenger who arrives on time for
     * a bus that already left waits a full headway. Five minutes late, ninety seconds early.
     */
    public static final int LATE_OPEN_SECONDS = 300;
    public static final int LATE_CLEAR_SECONDS = 180;
    public static final int EARLY_OPEN_SECONDS = -90;
    public static final int EARLY_CLEAR_SECONDS = -45;

    /**
     * How long a vehicle may stand at a stop before it is worth someone looking.
     *
     * <p>The seeded timetable allows 30 seconds. Three minutes is long enough that a heavy boarding,
     * a wheelchair ramp or a driver changeover has finished and something is actually wrong, and the
     * alert clears at two so a vehicle pulling away does not leave it hanging.
     */
    public static final int LONG_DWELL_OPEN_SECONDS = 180;
    public static final int LONG_DWELL_CLEAR_SECONDS = 120;

    private AlertRules() {
    }

    /**
     * Evaluates every rule against the current picture.
     *
     * @param liveFingerprints fingerprints that already have a live alert, so rules with hysteresis
     *                         can apply their wider "keep open" band rather than their opening one
     */
    public static List<AlertSignal> evaluate(
            List<LatestVehicleTelemetry> vehicles,
            List<HeadwayCondition> headwayConditions,
            Set<String> liveFingerprints
    ) {
        List<AlertSignal> signals = new ArrayList<>();

        for (LatestVehicleTelemetry vehicle : vehicles) {
            telemetryOffline(vehicle).ifPresent(signals::add);
            routeDeviation(vehicle, liveFingerprints).ifPresent(signals::add);
            scheduleAdherence(vehicle, liveFingerprints).forEach(signals::add);
            longDwell(vehicle, liveFingerprints).ifPresent(signals::add);
            lowBattery(vehicle, liveFingerprints).ifPresent(signals::add);
            overCapacity(vehicle, liveFingerprints).ifPresent(signals::add);
        }

        signals.addAll(headway(headwayConditions));
        return signals;
    }

    /**
     * A vehicle that has stopped reporting.
     *
     * <p>Only OFFLINE raises an alert. STALE is shown in the UI but is within the range of ordinary
     * network behaviour, and alerting on it would mean alerting constantly.
     */
    private static java.util.Optional<AlertSignal> telemetryOffline(LatestVehicleTelemetry vehicle) {
        if (vehicle.connectivityState() != ConnectivityState.OFFLINE) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(AlertSignal.forVehicle(
                AlertType.TELEMETRY_OFFLINE,
                vehicle.vehicleId(),
                vehicle.routeCode(),
                Map.of("telemetryAgeSeconds", Math.round(vehicle.telemetryAgeSeconds()))));
    }

    private static java.util.Optional<AlertSignal> routeDeviation(
            LatestVehicleTelemetry vehicle,
            Set<String> liveFingerprints
    ) {
        if (vehicle.routeDeviationMeters() == null || vehicle.connectivityState() == ConnectivityState.OFFLINE) {
            // Without fresh telemetry the stored distance describes where the vehicle was, not where
            // it is; the offline alert is the honest one to raise.
            return java.util.Optional.empty();
        }

        double deviation = vehicle.routeDeviationMeters().doubleValue();
        String fingerprint = AlertType.ROUTE_DEVIATION.name() + "|" + vehicle.vehicleId();
        double threshold = liveFingerprints.contains(fingerprint)
                ? ROUTE_DEVIATION_CLEAR_METERS
                : ROUTE_DEVIATION_OPEN_METERS;

        if (deviation <= threshold) {
            return java.util.Optional.empty();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deviationMeters", Math.round(deviation));
        details.put("routeProgress", vehicle.routeProgress());

        return java.util.Optional.of(AlertSignal.forVehicle(
                AlertType.ROUTE_DEVIATION, vehicle.vehicleId(), vehicle.routeCode(), details));
    }

    /**
     * Running late or early against the trip's schedule.
     *
     * <p>Only measurable once the vehicle has called at a stop: between stops the stored deviation is
     * carried forward from the last call rather than interpolated, and a vehicle that has not reached
     * its first stop has nothing to be measured against.
     */
    private static List<AlertSignal> scheduleAdherence(
            LatestVehicleTelemetry vehicle,
            Set<String> liveFingerprints
    ) {
        if (vehicle.scheduleDeviationSeconds() == null
                || vehicle.connectivityState() == ConnectivityState.OFFLINE) {
            return List.of();
        }

        int deviation = vehicle.scheduleDeviationSeconds();
        List<AlertSignal> signals = new ArrayList<>();

        String lateFingerprint = AlertType.VEHICLE_LATE.name() + "|" + vehicle.vehicleId();
        int lateThreshold = liveFingerprints.contains(lateFingerprint)
                ? LATE_CLEAR_SECONDS
                : LATE_OPEN_SECONDS;
        if (deviation >= lateThreshold) {
            signals.add(AlertSignal.forVehicle(
                    AlertType.VEHICLE_LATE, vehicle.vehicleId(), vehicle.routeCode(),
                    scheduleDetails(vehicle, deviation)));
        }

        String earlyFingerprint = AlertType.VEHICLE_EARLY.name() + "|" + vehicle.vehicleId();
        int earlyThreshold = liveFingerprints.contains(earlyFingerprint)
                ? EARLY_CLEAR_SECONDS
                : EARLY_OPEN_SECONDS;
        if (deviation <= earlyThreshold) {
            signals.add(AlertSignal.forVehicle(
                    AlertType.VEHICLE_EARLY, vehicle.vehicleId(), vehicle.routeCode(),
                    scheduleDetails(vehicle, deviation)));
        }

        return signals;
    }

    /**
     * A vehicle that has stopped at a stop and not moved on.
     *
     * <p>Distinct from lateness, and worth its own type: a vehicle three minutes late somewhere along
     * the route is running badly, while one that has been standing at a stop for three minutes is
     * blocking it, and the two call for different responses. The dwell is only known because arrivals
     * are recorded and closed - without stop detection this rule has nothing to read.
     */
    private static java.util.Optional<AlertSignal> longDwell(
            LatestVehicleTelemetry vehicle,
            Set<String> liveFingerprints
    ) {
        if (vehicle.dwellSeconds() == null || vehicle.connectivityState() == ConnectivityState.OFFLINE) {
            // Not standing at a stop, or not reporting. A vehicle that went silent while at a stop is
            // an offline vehicle, not a long dwell: nothing has been heard from it either way.
            return java.util.Optional.empty();
        }

        String fingerprint = AlertType.LONG_DWELL.name() + "|" + vehicle.vehicleId();
        int threshold = liveFingerprints.contains(fingerprint)
                ? LONG_DWELL_CLEAR_SECONDS
                : LONG_DWELL_OPEN_SECONDS;

        if (vehicle.dwellSeconds() < threshold) {
            return java.util.Optional.empty();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dwellSeconds", vehicle.dwellSeconds());
        details.put("stopName", vehicle.dwellingAtStopName());
        details.put("tripCode", vehicle.tripCode());

        return java.util.Optional.of(AlertSignal.forVehicle(
                AlertType.LONG_DWELL, vehicle.vehicleId(), vehicle.routeCode(), details));
    }

    private static Map<String, Object> scheduleDetails(LatestVehicleTelemetry vehicle, int deviation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scheduleDeviationSeconds", deviation);
        details.put("tripCode", vehicle.tripCode());
        details.put("nextStopName", vehicle.nextStopName());
        return details;
    }

    private static java.util.Optional<AlertSignal> lowBattery(
            LatestVehicleTelemetry vehicle,
            Set<String> liveFingerprints
    ) {
        if (vehicle.batteryPercent() == null) {
            return java.util.Optional.empty();
        }

        String fingerprint = AlertType.LOW_BATTERY.name() + "|" + vehicle.vehicleId();
        int threshold = liveFingerprints.contains(fingerprint)
                ? LOW_BATTERY_CLEAR_PERCENT
                : LOW_BATTERY_OPEN_PERCENT;

        if (vehicle.batteryPercent() > threshold) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(AlertSignal.forVehicle(
                AlertType.LOW_BATTERY,
                vehicle.vehicleId(),
                vehicle.routeCode(),
                Map.of("batteryPercent", vehicle.batteryPercent())));
    }

    private static java.util.Optional<AlertSignal> overCapacity(
            LatestVehicleTelemetry vehicle,
            Set<String> liveFingerprints
    ) {
        if (vehicle.capacity() <= 0) {
            return java.util.Optional.empty();
        }

        double ratio = (double) vehicle.occupancyEstimate() / vehicle.capacity();
        String fingerprint = AlertType.OVER_CAPACITY.name() + "|" + vehicle.vehicleId();
        double threshold = liveFingerprints.contains(fingerprint)
                ? OVER_CAPACITY_CLEAR_RATIO
                : OVER_CAPACITY_OPEN_RATIO;

        if (ratio < threshold) {
            return java.util.Optional.empty();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("occupancyEstimate", vehicle.occupancyEstimate());
        details.put("capacity", vehicle.capacity());

        return java.util.Optional.of(AlertSignal.forVehicle(
                AlertType.OVER_CAPACITY, vehicle.vehicleId(), vehicle.routeCode(), details));
    }

    /**
     * Confirmed headway conditions become alerts.
     *
     * <p>Only confirmed ones: the headway rules already made a pair wait 90 seconds before asserting
     * it, and a condition still being watched is not yet a claim about the service.
     */
    private static List<AlertSignal> headway(List<HeadwayCondition> conditions) {
        List<AlertSignal> signals = new ArrayList<>();

        for (HeadwayCondition condition : conditions) {
            if (!condition.confirmed()) {
                continue;
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("headwaySeconds", condition.headwaySeconds());
            details.put("targetHeadwaySeconds", condition.targetHeadwaySeconds());
            details.put("ratioToTarget", Math.round(condition.ratioToTarget() * 100) / 100.0);

            AlertType type = condition.type() == HeadwayConditionType.BUNCHING
                    ? AlertType.BUNCHING
                    : AlertType.EXCESSIVE_GAP;

            signals.add(AlertSignal.forPair(
                    type,
                    condition.routeCode(),
                    condition.leaderVehicleId(),
                    condition.followerVehicleId(),
                    details));
        }

        return signals;
    }
}
