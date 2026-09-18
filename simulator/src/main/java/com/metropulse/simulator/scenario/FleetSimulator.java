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
 * Drives a fleet of synthetic vehicles along a route shape, to a timetable.
 *
 * <p>Vehicles run scheduled trips rather than circling the shape: each one takes a departure, works
 * its way along the pattern - driving between stops, standing at them - and then takes another. What
 * the platform downstream measures is how well that went, and every bit of it is a consequence of the
 * movement rather than a declaration. The simulator never says "these two are bunched" or "this one
 * is four minutes late"; it makes one vehicle crawl and lets the backend work out the rest.
 *
 * <p>Randomness comes from a seeded {@link Random}, so a run is reproducible from its seed.
 */
public class FleetSimulator {

    /** Percent of battery used per kilometre; a portfolio figure, not a measured vehicle spec. */
    private static final double BATTERY_PERCENT_PER_KM = 0.6;

    /** How much faster than its scheduled speed a vehicle may drive to make up lost time. */
    private static final double MAXIMUM_CATCH_UP = 1.8;

    /** Tick-to-tick variation in how far a vehicle actually gets, as a share of what it intended. */
    private static final double SPEED_JITTER = 0.12;

    /**
     * How close a vehicle may get to the one ahead of it before it has to hold station.
     *
     * <p>Without this, a vehicle making up time drives straight through the one in front and out the
     * other side, and bunching never lasts: the pair re-forms, swaps roles and dissolves every few
     * ticks. Real vehicles queue behind each other, and it is that queueing which makes bunching a
     * sustained condition rather than a flicker.
     */
    private static final double MINIMUM_FOLLOWING_GAP_METERS = 12.0;

    private final RoutePath route;
    private final ScenarioType scenario;
    private final Duration tickInterval;
    private final Random random;
    private final List<SimulatedVehicle> fleet;
    private final String runId;
    private final TripPattern pattern;

    /**
     * The speed the timetable implies: the shape's length over the driving time the schedule allows.
     *
     * <p>Deriving it rather than picking it is what keeps the simulated network self-consistent. A
     * fleet driven at a speed of its own choosing would reach every stop minutes early or late for no
     * reason anyone could point at, and the punctuality figures downstream would be measuring that
     * discrepancy rather than anything about the service.
     */
    private final double scheduledSpeedKph;

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
        this.pattern = new TripPattern(route.vertexProgress());
        this.scheduledSpeedKph = route.lengthMeters() / TripPattern.runningSeconds() * 3.6;
        this.fleet = new ArrayList<>(vehicleIds.size());

        for (int index = 0; index < vehicleIds.size(); index++) {
            // Everyone starts at the western terminal. Spacing is then a consequence of the departures
            // they are each given rather than of an arrangement chosen here, which is the point: the
            // gaps the platform measures have to come from the timetable being run, or not being.
            fleet.add(new SimulatedVehicle(
                    vehicleIds.get(index),
                    0.0,
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

        // The queue is worked out before anyone moves. Deciding it afterwards would let a vehicle that
        // overshot the one in front within a single tick be treated as the leader, and the rule would
        // then push the vehicle it had just passed backwards.
        List<QueuePosition> queueOrder = fleet.stream()
                .map(vehicle -> new QueuePosition(vehicle, vehicle.routeProgress()))
                .sorted(Comparator.comparingDouble(QueuePosition::progressBefore))
                .toList();

        for (int index = 0; index < fleet.size(); index++) {
            SimulatedVehicle vehicle = fleet.get(index);
            ScenarioAdjustment adjustment = ScenarioProfile.adjustmentFor(scenario, index, tickNumber);
            TripSchedule trip = TripSchedule.forVehicle(index, fleet.size(), now);

            if (!trip.tripCode().equals(vehicle.tripCode())) {
                vehicle.beginTrip(trip.tripCode());
            }

            drive(vehicle, pattern.progressAt(trip.elapsed(now).toSeconds()), adjustment);
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
                    vehicle.tripCode(),
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
     * Moves one vehicle towards where its timetable says it should be.
     *
     * <p>A vehicle drives at whatever speed gets it to its scheduled position, up to a limit: it can
     * press on to make up a delay but not teleport, and at a stop - where the schedule holds still -
     * it stands still too, which is what makes it observable as having called there rather than
     * having driven past. Time lost to a scenario is therefore lost for real, and comes back as
     * lateness the platform measures for itself.
     */
    private void drive(SimulatedVehicle vehicle, double scheduledProgress, ScenarioAdjustment adjustment) {
        double tickSeconds = tickInterval.toMillis() / 1000.0;
        double jitter = 1.0 + (random.nextDouble() - 0.5) * 2.0 * SPEED_JITTER;

        double wantedMeters = Math.max(0.0,
                (scheduledProgress - vehicle.routeProgress()) * route.lengthMeters());
        double normalMeters = scheduledSpeedKph / 3.6 * tickSeconds;

        // Pressing on is for a vehicle that has genuinely lost time, not for the metre or two of slack
        // that ordinary variation leaves behind. Without that distinction a vehicle would be allowed
        // to drive at its catch-up limit every tick, overshoot its scheduled position, wait for the
        // timetable to come back to it, and report a speed that alternated between double and zero.
        boolean delayed = wantedMeters > normalMeters * 2.0;
        double allowedMeters = normalMeters * jitter * adjustment.speedFactor()
                * (delayed ? MAXIMUM_CATCH_UP : 1.0);
        double metersTravelled = Math.min(wantedMeters, allowedMeters);

        vehicle.setSpeedKph(metersTravelled / tickSeconds * 3.6);
        vehicle.advance(metersTravelled / route.lengthMeters());
        vehicle.drainBattery(metersTravelled / 1000.0 * BATTERY_PERCENT_PER_KM);
    }

    /**
     * Stops any vehicle that has caught the one ahead of it from passing through it.
     *
     * <p>Vehicles are walked from the front of the queue backwards, so a queue forms properly: the
     * second vehicle holds behind the first, the third behind the second, and so on.
     *
     * @param queueOrder the fleet ordered by position as it was before this tick's movement
     */
    private void holdVehiclesBehindTheirLeaders(List<QueuePosition> queueOrder) {
        double minimumGap = MINIMUM_FOLLOWING_GAP_METERS / route.lengthMeters();

        for (int index = queueOrder.size() - 2; index >= 0; index--) {
            QueuePosition follower = queueOrder.get(index);
            QueuePosition leader = queueOrder.get(index + 1);

            if (leader.progressBefore() <= follower.progressBefore()) {
                // Not actually in front of it. Vehicles sharing a terminal between trips are all at
                // the same point, and a terminal holds several buses at once: treating one of them as
                // blocking the others would stop the whole layover from ever leaving.
                continue;
            }

            if (follower.vehicle().speedKph() == 0.0) {
                // Standing at a stop or waiting for its departure. It cannot run into anything, and
                // pushing it back would take it off the stop it is serving.
                continue;
            }

            if (leader.vehicle().routeProgress() < leader.progressBefore()) {
                // The leader has finished and gone back to the terminal to start its next trip. It
                // was in front when the queue was worked out and is now at the beginning of the
                // route, so holding the follower behind it drags a vehicle three quarters of the way
                // through its own run back to the start.
                continue;
            }

            if (leader.vehicle().routeProgress() >= 1.0) {
                // The leader has finished its trip and is waiting out its layover at the terminal. A
                // terminal holds several vehicles, and treating the one already there as blocking the
                // road would stop the next arrival twelve metres short of the final stop - close
                // enough to look right on a map, far enough that it is never recorded as calling
                // there, and the last stop of every trip would go missing from punctuality.
                continue;
            }

            double gap = leader.vehicle().routeProgress() - follower.vehicle().routeProgress();
            if (gap < minimumGap) {
                follower.vehicle().holdAt(Math.max(0.0, leader.vehicle().routeProgress() - minimumGap));
            }
        }
    }

    /**
     * A vehicle and where it stood before this tick's movement.
     *
     * <p>Positions are remembered rather than read back because the queue has to be judged on where
     * everyone was, not on where they ended up.
     */
    private record QueuePosition(SimulatedVehicle vehicle, double progressBefore) {
    }

    public List<SimulatedVehicle> fleet() {
        return List.copyOf(fleet);
    }

    public long tickNumber() {
        return tickNumber;
    }

    /** The speed the timetable implies for this shape, exposed so tests can state it in real units. */
    public double scheduledSpeedKph() {
        return scheduledSpeedKph;
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
