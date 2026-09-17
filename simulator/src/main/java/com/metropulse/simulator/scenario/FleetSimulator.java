package com.metropulse.simulator.scenario;

import com.metropulse.simulator.route.GeoPoint;
import com.metropulse.simulator.route.RoutePath;
import com.metropulse.simulator.telemetry.TelemetryIngestPayload;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * How close a vehicle may get to the one ahead of it before it has to hold station.
     *
     * <p>Without this, a fast follower drives straight through a slow leader and out the other side,
     * and bunching never lasts: the pair re-forms, swaps roles and dissolves every few ticks. Real
     * vehicles queue behind each other, and it is that queueing which makes bunching a sustained
     * condition rather than a flicker.
     */
    private static final double MINIMUM_FOLLOWING_GAP_METERS = 12.0;

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

        // The queue order is taken before anyone moves. Deciding it afterwards would let a fast
        // follower that overshot its leader within one tick be treated as the leader, and the rule
        // would then push the vehicle it just passed backwards.
        List<SimulatedVehicle> queueOrder = new ArrayList<>(fleet);
        queueOrder.sort(Comparator.comparingDouble(SimulatedVehicle::routeProgress));

        for (int index = 0; index < fleet.size(); index++) {
            SimulatedVehicle vehicle = fleet.get(index);
            ScenarioAdjustment adjustment = ScenarioProfile.adjustmentFor(scenario, index, tickNumber);

            double speedKph = (CRUISE_SPEED_KPH + random.nextDouble() * SPEED_JITTER_KPH) * adjustment.speedFactor();
            vehicle.setSpeedKph(speedKph);

            double metersTravelled = speedKph * 1000.0 / 3600.0 * tickInterval.toMillis() / 1000.0;
            vehicle.advance(metersTravelled / route.lengthMeters());
            vehicle.drainBattery(metersTravelled / 1000.0 * BATTERY_PERCENT_PER_KM);
            vehicle.setOccupancyEstimate(nextOccupancy(vehicle.occupancyEstimate()));
        }

        holdVehiclesBehindTheirLeaders(queueOrder);

        for (int index = 0; index < fleet.size(); index++) {
            SimulatedVehicle vehicle = fleet.get(index);
            ScenarioAdjustment adjustment = ScenarioProfile.adjustmentFor(scenario, index, tickNumber);

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

    /**
     * Stops any vehicle that has caught the one ahead of it from passing through it.
     *
     * <p>Vehicles are walked from the front of the queue backwards, so a queue forms properly: the
     * second vehicle holds behind the first, the third behind the second, and so on.
     *
     * @param queueOrder the fleet ordered by position as it was before this tick's movement
     */
    private void holdVehiclesBehindTheirLeaders(List<SimulatedVehicle> queueOrder) {
        double minimumGap = MINIMUM_FOLLOWING_GAP_METERS / route.lengthMeters();

        for (int index = queueOrder.size() - 2; index >= 0; index--) {
            SimulatedVehicle follower = queueOrder.get(index);
            SimulatedVehicle leader = queueOrder.get(index + 1);

            if (forwardGap(follower, leader) < minimumGap) {
                follower.holdAt(wrap(leader.routeProgress() - minimumGap));
            }
        }
    }

    /** Distance forward along the shape from the follower to the leader, as a fraction of the shape. */
    private static double forwardGap(SimulatedVehicle follower, SimulatedVehicle leader) {
        double gap = leader.routeProgress() - follower.routeProgress();
        return gap < 0 ? gap + 1.0 : gap;
    }

    private static double wrap(double progress) {
        return progress - Math.floor(progress);
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
