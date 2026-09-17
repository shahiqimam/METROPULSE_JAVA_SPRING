package com.metropulse.simulator.scenario;

import com.metropulse.simulator.route.GeoPoint;
import com.metropulse.simulator.route.RoutePath;
import com.metropulse.simulator.telemetry.TelemetryIngestPayload;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Drives a fleet of synthetic vehicles along a route shape.
 *
 * <p>Vehicles start evenly spaced around the shape and move by distance each tick, so their relative
 * spacing is a consequence of how fast each one is driven. That is what makes bunching and gaps
 * observable downstream: the simulator never declares "these two are bunched", it just makes one
 * vehicle crawl and lets the backend measure the result.
 *
 * <p>Randomness comes from a seeded {@link Random}, so a run is reproducible from its seed.
 */
public class FleetSimulator {

    private static final double CRUISE_SPEED_KPH = 26.0;
    private static final double SPEED_JITTER_KPH = 6.0;

    /** Percent of battery used per kilometre; a portfolio figure, not a measured vehicle spec. */
    private static final double BATTERY_PERCENT_PER_KM = 0.6;

    private final RoutePath route;
    private final ScenarioType scenario;
    private final Duration tickInterval;
    private final Random random;
    private final List<SimulatedVehicle> fleet;
    private final String runId;

    private long tickNumber;

    public FleetSimulator(
            RoutePath route,
            List<String> vehicleIds,
            ScenarioType scenario,
            Duration tickInterval,
            long seed,
            String runId
    ) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            throw new IllegalArgumentException("A fleet needs at least one vehicle id.");
        }
        this.route = route;
        this.scenario = scenario;
        this.tickInterval = tickInterval;
        this.random = new Random(seed);
        this.runId = runId;
        this.fleet = new ArrayList<>(vehicleIds.size());

        for (int index = 0; index < vehicleIds.size(); index++) {
            fleet.add(new SimulatedVehicle(
                    vehicleIds.get(index),
                    (double) index / vehicleIds.size(),
                    ScenarioProfile.initialBatteryPercent(scenario, index),
                    12 + random.nextInt(24)));
        }
    }

    /**
     * Advances every vehicle by one tick.
     *
     * @return the telemetry to send; vehicles that are silent under the active scenario are absent
     */
    public List<TelemetryIngestPayload> tick(Instant now) {
        tickNumber++;
        List<TelemetryIngestPayload> payloads = new ArrayList<>(fleet.size());

        for (int index = 0; index < fleet.size(); index++) {
            SimulatedVehicle vehicle = fleet.get(index);
            ScenarioAdjustment adjustment = ScenarioProfile.adjustmentFor(scenario, index, tickNumber);

            double speedKph = (CRUISE_SPEED_KPH + random.nextDouble() * SPEED_JITTER_KPH) * adjustment.speedFactor();
            vehicle.setSpeedKph(speedKph);

            double metersTravelled = speedKph * 1000.0 / 3600.0 * tickInterval.toMillis() / 1000.0;
            vehicle.advance(metersTravelled / route.lengthMeters());
            vehicle.drainBattery(metersTravelled / 1000.0 * BATTERY_PERCENT_PER_KM);
            vehicle.setOccupancyEstimate(nextOccupancy(vehicle.occupancyEstimate()));

            if (!adjustment.reporting()) {
                continue;
            }

            double heading = route.headingAt(vehicle.routeProgress());
            GeoPoint onPath = route.pointAt(vehicle.routeProgress());
            GeoPoint reported = route.offsetFromPath(onPath, heading, adjustment.lateralOffsetMeters());

            payloads.add(new TelemetryIngestPayload(
                    "sim-" + runId + "-" + vehicle.vehicleId() + "-" + tickNumber,
                    vehicle.vehicleId(),
                    now,
                    reported.latitude(),
                    reported.longitude(),
                    round(vehicle.speedKph(), 2),
                    round(heading, 2),
                    vehicle.occupancyEstimate(),
                    (int) Math.round(vehicle.batteryPercent())));
        }

        return payloads;
    }

    public List<SimulatedVehicle> fleet() {
        return List.copyOf(fleet);
    }

    public long tickNumber() {
        return tickNumber;
    }

    private int nextOccupancy(int current) {
        int change = random.nextInt(7) - 3;
        return Math.max(0, Math.min(90, current + change));
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
