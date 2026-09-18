package com.metropulse.simulator.scenario;

import com.metropulse.simulator.route.GeoPoint;
import com.metropulse.simulator.route.RoutePath;
import com.metropulse.simulator.telemetry.TelemetryIngestPayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulator's job is to produce movement a control centre could plausibly be watching, and to
 * produce it without ever asserting the thing the platform is supposed to work out for itself.
 *
 * <p>Time advances with the ticks here rather than standing still, because the fleet runs to a
 * timetable: a test that passed the same instant to every tick would be asking vehicles to keep a
 * schedule in a world where the clock had stopped.
 */
class FleetSimulatorTest {

    private static final List<String> FLEET = List.of("BUS-042", "BUS-101", "BUS-204", "BUS-317");
    private static final Duration TICK = Duration.ofSeconds(2);

    /**
     * 05:00 in the network's own timezone: the first departure of the seeded service day.
     *
     * <p>Starting here rather than at an arbitrary hour is what makes the fleet's state knowable. At
     * any other moment each vehicle is somewhere different in its own block - which is the point of
     * the blocks - and "the second vehicle has not left yet" would be a statement about the clock the
     * test happened to pick.
     */
    private static final Instant SERVICE_START = Instant.parse("2026-09-17T09:00:00Z");

    /** The seeded pattern: 390 seconds from the first departure to the last arrival. */
    private static final int TRIP_SECONDS = 390;

    /** The same trip counted in ticks, which is what the simulator advances in. */
    private static final int TRIP_TICKS = TRIP_SECONDS / (int) TICK.toSeconds();

    private final RoutePath route = new RoutePath(List.of(
            new GeoPoint(40.7128, -74.0060),
            new GeoPoint(40.7140, -74.0020),
            new GeoPoint(40.7152, -73.9980),
            new GeoPoint(40.7163, -73.9945),
            new GeoPoint(40.7178, -73.9900)));

    @Test
    void normalOperationKeepsEveryVehicleOnTheRouteShape() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        for (TelemetryIngestPayload payload : run(simulator, 200)) {
            assertThat(distanceToShape(payload))
                    .as("%s should stay on the route shape", payload.vehicleId())
                    .isLessThan(1.0);
        }
    }

    @Test
    void everyVehicleWaitsAtTheTerminalUntilItsOwnDepartureTime() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        // A minute in: the 05:00 departure has gone, the 05:02, 05:04 and 05:06 have not.
        run(simulator, 30);

        assertThat(simulator.fleet().getFirst().routeProgress()).isGreaterThan(0.0);
        assertThat(simulator.fleet().subList(1, 4))
                .as("vehicles hold at the terminal rather than leaving early")
                .allSatisfy(vehicle -> assertThat(vehicle.routeProgress()).isZero());
    }

    @Test
    void aVehicleRunsTheWholeTripInTheTimeTheTimetableAllows() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        run(simulator, TRIP_TICKS / 2);
        assertThat(simulator.fleet().getFirst().routeProgress())
                .as("halfway through the trip it is somewhere in the middle of the route")
                .isBetween(0.2, 0.8);

        run(simulator, TRIP_TICKS / 2 + 2);
        assertThat(simulator.fleet().getFirst().routeProgress())
                .as("it reaches the far terminal as its trip ends")
                .isEqualTo(1.0);
    }

    @Test
    void aVehicleStandsStillAtEveryStopOnItsTrip() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);
        double[] stops = route.vertexProgress();

        List<Double> stationaryAt = new ArrayList<>();
        for (int tick = 0; tick < TRIP_TICKS + 5; tick++) {
            simulator.tick(SERVICE_START.plus(TICK.multipliedBy(tick + 1)));
            SimulatedVehicle vehicle = simulator.fleet().getFirst();
            if (vehicle.speedKph() == 0.0) {
                stationaryAt.add(vehicle.routeProgress());
            }
        }

        // Standing at a stop is what makes a vehicle observable as having called there rather than
        // driven past, and it is the only reason schedule adherence can be measured at all.
        for (double stop : stops) {
            assertThat(stationaryAt)
                    .as("the vehicle should stand still at the stop at %.3f along the route", stop)
                    .anySatisfy(progress -> assertThat(progress).isEqualTo(stop, within(0.001)));
        }
    }

    @Test
    void drivingSpeedIsTheOneTheTimetableImplies() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        List<Double> movingSpeeds = run(simulator, TRIP_TICKS).stream()
                .map(TelemetryIngestPayload::speedKph)
                .filter(speed -> speed > 0.0)
                .toList();

        double average = movingSpeeds.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertThat(average)
                .as("a fleet driven at a speed of its own choosing would make every deviation meaningless")
                .isCloseTo(simulator.scheduledSpeedKph(), within(2.0));
        assertThat(simulator.scheduledSpeedKph())
                .as("1,459 m of shape in 270 s of driving")
                .isCloseTo(19.4, within(0.5));
    }

    @Test
    void aVehicleTakesAFreshTripOnceItsOwnIsDone() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);
        run(simulator, 10);
        String firstTrip = simulator.fleet().getFirst().tripCode();

        // A vehicle's own block comes round again after a full cycle of the fleet: eight minutes.
        run(simulator, 250);

        SimulatedVehicle vehicle = simulator.fleet().getFirst();
        assertThat(vehicle.tripCode()).isNotEqualTo(firstTrip);
        assertThat(vehicle.tripCode()).matches("M42-WKD-\\d{4}-EAST");
        assertThat(vehicle.routeProgress())
                .as("a new trip starts from the terminal, not from wherever the last one ended")
                .isLessThan(0.5);
    }

    @Test
    void everyVehicleCompletesItsTripBeforeBeingGivenAnother() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        // Whether each vehicle ever reaches the far terminal, over more than a full cycle of the fleet.
        List<String> reachedTheEnd = new ArrayList<>();
        for (int tick = 1; tick <= 500; tick++) {
            simulator.tick(SERVICE_START.plus(TICK.multipliedBy(tick)));
            for (SimulatedVehicle vehicle : simulator.fleet()) {
                if (vehicle.routeProgress() == 1.0 && !reachedTheEnd.contains(vehicle.vehicleId())) {
                    reachedTheEnd.add(vehicle.vehicleId());
                }
            }
        }

        // Each vehicle's block is offset by its own place in the fleet. Without that they all change
        // trip at the same instant, and the last one is handed a new departure while it is still part
        // way through the previous run - so it never finishes one.
        assertThat(reachedTheEnd).containsExactlyInAnyOrderElementsOf(FLEET);
    }

    @Test
    void theSameSeedProducesTheSameRun() {
        List<TelemetryIngestPayload> first = run(simulator(ScenarioType.NORMAL_OPERATION), 60);
        List<TelemetryIngestPayload> second = run(simulator(ScenarioType.NORMAL_OPERATION), 60);

        assertThat(positionsOf(first)).isEqualTo(positionsOf(second));
    }

    @Test
    void aSlowedVehicleFallsBehindItsTimetableRatherThanCatchingUpForFree() {
        FleetSimulator normal = simulator(ScenarioType.NORMAL_OPERATION);
        FleetSimulator bunching = simulator(ScenarioType.BUNCHING);

        run(normal, 120);
        run(bunching, 120);

        // BUS-042 crawls under BUNCHING. Time it loses has to stay lost, or the delay the platform is
        // meant to detect would quietly undo itself.
        assertThat(bunching.fleet().getFirst().routeProgress())
                .isLessThan(normal.fleet().getFirst().routeProgress());
    }

    @Test
    void aFollowerClosesOnTheSlowLeaderWithoutDrivingThroughIt() {
        FleetSimulator simulator = simulator(ScenarioType.BUNCHING);

        run(simulator, 200);

        SimulatedVehicle slowLeader = simulator.fleet().getFirst();
        double closest = simulator.fleet().stream()
                .filter(vehicle -> !vehicle.vehicleId().equals(slowLeader.vehicleId()))
                .filter(vehicle -> vehicle.routeProgress() > 0.0)
                .mapToDouble(vehicle -> slowLeader.routeProgress() - vehicle.routeProgress())
                .filter(gap -> gap >= 0.0)
                .min()
                .orElseThrow();

        // Someone has caught it up, and nobody has passed through it.
        assertThat(closest).isLessThan(0.25);
        assertThat(closest).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void routeDeviationPlacesOneVehicleWellOffTheShapeAndLeavesTheRestOnIt() {
        FleetSimulator simulator = simulator(ScenarioType.ROUTE_DEVIATION);

        List<TelemetryIngestPayload> payloads = run(simulator, 60);

        assertThat(distanceToShape(lastPayloadFor(payloads, "BUS-042"))).isGreaterThan(100.0);
        assertThat(distanceToShape(lastPayloadFor(payloads, "BUS-101"))).isLessThan(1.0);
    }

    @Test
    void telemetryLossSilencesOneVehicleAndLaterRestoresIt() {
        FleetSimulator simulator = simulator(ScenarioType.TELEMETRY_LOSS);

        List<TelemetryIngestPayload> duringOutage = simulator.tick(SERVICE_START.plus(TICK));
        assertThat(reportingVehicles(duringOutage)).doesNotContain("BUS-204").hasSize(3);

        List<TelemetryIngestPayload> afterOutage = null;
        for (int tick = 2; tick < 200; tick++) {
            List<TelemetryIngestPayload> payloads = simulator.tick(SERVICE_START.plus(TICK.multipliedBy(tick)));
            if (reportingVehicles(payloads).contains("BUS-204")) {
                afterOutage = payloads;
                break;
            }
        }

        assertThat(afterOutage).as("the silent vehicle should start reporting again").isNotNull();
        assertThat(reportingVehicles(afterOutage)).hasSize(4);
    }

    @Test
    void longDwellHoldsOneVehicleAtTheTerminalWhileTheOthersRunTheirTrips() {
        FleetSimulator simulator = simulator(ScenarioType.LONG_DWELL);

        run(simulator, 150);

        assertThat(simulator.fleet().get(1).speedKph()).isZero();
        assertThat(simulator.fleet().get(1).routeProgress()).isZero();
        assertThat(simulator.fleet().getFirst().routeProgress()).isGreaterThan(0.5);
    }

    @Test
    void lowBatteryScenarioStartsOneVehicleNearlyEmptyAndKeepsDraining() {
        FleetSimulator simulator = simulator(ScenarioType.EV_LOW_BATTERY);

        List<TelemetryIngestPayload> payloads = simulator.tick(SERVICE_START.plus(TICK));
        int startingBattery = payloadFor(payloads, "BUS-042").batteryPercent();
        assertThat(startingBattery).isLessThan(20);
        assertThat(payloadFor(payloads, "BUS-101").batteryPercent()).isGreaterThan(50);

        run(simulator, 400);

        assertThat(simulator.fleet().getFirst().batteryPercent()).isLessThan(startingBattery);
        assertThat(simulator.fleet().getFirst().batteryPercent()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void multiIncidentCombinesDeviationHoldingAndSilence() {
        FleetSimulator simulator = simulator(ScenarioType.MULTI_INCIDENT);

        // The first tick: BUS-204 is inside its silent window, which does not last the whole run.
        List<TelemetryIngestPayload> payloads = simulator.tick(SERVICE_START.plus(TICK));

        assertThat(distanceToShape(payloadFor(payloads, "BUS-042"))).isGreaterThan(100.0);
        assertThat(payloadFor(payloads, "BUS-101").speedKph()).isZero();
        assertThat(reportingVehicles(payloads)).doesNotContain("BUS-204");
    }

    @Test
    void recoveryLosesGroundWhileDegradedAndMakesItUpAfterwards() {
        FleetSimulator simulator = simulator(ScenarioType.RECOVERY);

        // The degraded phase is the first sixty ticks; the vehicle cannot keep its timetable.
        run(simulator, 60);
        double afterDegradedPhase = simulator.fleet().getFirst().routeProgress();

        run(simulator, 60);
        double afterRecovery = simulator.fleet().getFirst().routeProgress();

        assertThat(afterRecovery - afterDegradedPhase)
                .as("with the degradation lifted it presses on to make up the lost ground")
                .isGreaterThan(afterDegradedPhase);
    }

    private FleetSimulator simulator(ScenarioType scenario) {
        return new FleetSimulator(route, FLEET, scenario, TICK, 42L, "test-run");
    }

    /** Runs the given number of ticks with the clock advancing by one tick each time. */
    private List<TelemetryIngestPayload> run(FleetSimulator simulator, int ticks) {
        List<TelemetryIngestPayload> payloads = new ArrayList<>();
        long from = simulator.tickNumber();
        for (long tick = from + 1; tick <= from + ticks; tick++) {
            payloads.addAll(simulator.tick(SERVICE_START.plus(TICK.multipliedBy(tick))));
        }
        return payloads;
    }

    private List<String> positionsOf(List<TelemetryIngestPayload> payloads) {
        return payloads.stream()
                .map(payload -> "%s:%.7f,%.7f:%.2f".formatted(
                        payload.vehicleId(), payload.latitude(), payload.longitude(), payload.speedKph()))
                .toList();
    }

    private List<String> reportingVehicles(List<TelemetryIngestPayload> payloads) {
        return payloads.stream().map(TelemetryIngestPayload::vehicleId).distinct().toList();
    }

    private TelemetryIngestPayload payloadFor(List<TelemetryIngestPayload> payloads, String vehicleId) {
        return payloads.stream()
                .filter(payload -> payload.vehicleId().equals(vehicleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No payload for " + vehicleId));
    }

    private TelemetryIngestPayload lastPayloadFor(List<TelemetryIngestPayload> payloads, String vehicleId) {
        return payloads.reversed().stream()
                .filter(payload -> payload.vehicleId().equals(vehicleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No payload for " + vehicleId));
    }

    /** Shortest distance from a reported position to the route shape, sampled along the line. */
    private double distanceToShape(TelemetryIngestPayload payload) {
        GeoPoint reported = new GeoPoint(payload.latitude(), payload.longitude());
        double closest = Double.MAX_VALUE;
        for (double progress = 0.0; progress <= 1.0; progress += 0.0005) {
            closest = Math.min(closest, RoutePath.distanceMeters(reported, route.pointAt(progress)));
        }
        return closest;
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
