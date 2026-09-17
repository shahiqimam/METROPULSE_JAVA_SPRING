package com.metropulse.simulator.scenario;

import com.metropulse.simulator.route.GeoPoint;
import com.metropulse.simulator.route.RoutePath;
import com.metropulse.simulator.telemetry.TelemetryIngestPayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FleetSimulatorTest {

    private static final List<String> FLEET = List.of("BUS-042", "BUS-101", "BUS-204", "BUS-317");
    private static final Duration TICK = Duration.ofSeconds(2);
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");

    private final RoutePath route = new RoutePath(List.of(
            new GeoPoint(40.7128, -74.0060),
            new GeoPoint(40.7140, -74.0020),
            new GeoPoint(40.7152, -73.9980),
            new GeoPoint(40.7163, -73.9945),
            new GeoPoint(40.7178, -73.9900)));

    @Test
    void normalOperationKeepsEveryVehicleOnTheRouteShape() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        for (int tick = 0; tick < 60; tick++) {
            for (TelemetryIngestPayload payload : simulator.tick(NOW)) {
                assertThat(distanceToShape(payload))
                        .as("%s should stay on the route shape", payload.vehicleId())
                        .isLessThan(1.0);
            }
        }
    }

    @Test
    void vehiclesStartEvenlySpacedAndAdvanceAlongTheShape() {
        FleetSimulator simulator = simulator(ScenarioType.NORMAL_OPERATION);

        assertThat(simulator.fleet().stream().map(SimulatedVehicle::routeProgress))
                .containsExactly(0.0, 0.25, 0.5, 0.75);

        simulator.tick(NOW);

        assertThat(simulator.fleet().getFirst().routeProgress()).isGreaterThan(0.0);
        assertThat(simulator.fleet().getFirst().speedKph()).isBetween(26.0, 32.0);
    }

    @Test
    void theSameSeedProducesTheSameRun() {
        List<TelemetryIngestPayload> first = run(simulator(ScenarioType.NORMAL_OPERATION), 20);
        List<TelemetryIngestPayload> second = run(simulator(ScenarioType.NORMAL_OPERATION), 20);

        assertThat(positionsOf(first)).isEqualTo(positionsOf(second));
    }

    @Test
    void bunchingClosesTheGapBehindTheSlowLeader() {
        FleetSimulator simulator = simulator(ScenarioType.BUNCHING);
        double initialTightestGap = tightestGap(simulator);

        IntStream.range(0, 120).forEach(tick -> simulator.tick(NOW));

        // Somewhere in the fleet, two vehicles are now closer together than any pair started out.
        assertThat(tightestGap(simulator)).isLessThan(initialTightestGap);
    }

    @Test
    void aFollowerHoldsStationBehindASlowLeaderRatherThanDrivingThroughIt() {
        FleetSimulator simulator = simulator(ScenarioType.BUNCHING);

        // BUS-042 crawls; the vehicle behind it closes up and then has to queue.
        IntStream.range(0, 400).forEach(tick -> simulator.tick(NOW));

        SimulatedVehicle slowLeader = simulator.fleet().getFirst();
        double closest = simulator.fleet().stream()
                .filter(vehicle -> !vehicle.vehicleId().equals(slowLeader.vehicleId()))
                .mapToDouble(vehicle -> gapBehind(vehicle, slowLeader))
                .min()
                .orElseThrow();

        // Someone is queued right behind it, and nobody has passed through it.
        assertThat(closest).isLessThan(0.05);
        assertThat(closest).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void bunchingPersistsOnceItHasFormed() {
        FleetSimulator simulator = simulator(ScenarioType.BUNCHING);
        IntStream.range(0, 400).forEach(tick -> simulator.tick(NOW));

        // Once a queue has formed behind the slow leader it should still be there later, rather than
        // dissolving as vehicles drive through each other.
        double before = tightestGap(simulator);
        IntStream.range(0, 120).forEach(tick -> simulator.tick(NOW));

        assertThat(tightestGap(simulator)).isLessThan(0.08);
        assertThat(before).isLessThan(0.08);
    }

    @Test
    void routeDeviationPlacesOneVehicleWellOffTheShapeAndLeavesTheRestOnIt() {
        FleetSimulator simulator = simulator(ScenarioType.ROUTE_DEVIATION);

        List<TelemetryIngestPayload> payloads = simulator.tick(NOW);

        assertThat(distanceToShape(payloadFor(payloads, "BUS-042"))).isGreaterThan(100.0);
        assertThat(distanceToShape(payloadFor(payloads, "BUS-101"))).isLessThan(1.0);
    }

    @Test
    void telemetryLossSilencesOneVehicleAndLaterRestoresIt() {
        FleetSimulator simulator = simulator(ScenarioType.TELEMETRY_LOSS);

        List<TelemetryIngestPayload> duringOutage = simulator.tick(NOW);
        assertThat(reportingVehicles(duringOutage)).doesNotContain("BUS-204").hasSize(3);

        List<TelemetryIngestPayload> afterOutage = null;
        for (int tick = 0; tick < 200; tick++) {
            List<TelemetryIngestPayload> payloads = simulator.tick(NOW);
            if (reportingVehicles(payloads).contains("BUS-204")) {
                afterOutage = payloads;
                break;
            }
        }

        assertThat(afterOutage).as("the silent vehicle should start reporting again").isNotNull();
        assertThat(reportingVehicles(afterOutage)).hasSize(4);
    }

    @Test
    void longDwellHoldsOneVehicleStillWhileTheOthersKeepMoving() {
        FleetSimulator simulator = simulator(ScenarioType.LONG_DWELL);
        double progressBefore = simulator.fleet().get(1).routeProgress();

        IntStream.range(0, 30).forEach(tick -> simulator.tick(NOW));

        assertThat(simulator.fleet().get(1).speedKph()).isZero();
        assertThat(simulator.fleet().get(1).routeProgress()).isEqualTo(progressBefore);
        assertThat(simulator.fleet().get(2).routeProgress()).isNotEqualTo(progressBefore);
    }

    @Test
    void lowBatteryScenarioStartsOneVehicleNearlyEmptyAndKeepsDraining() {
        FleetSimulator simulator = simulator(ScenarioType.EV_LOW_BATTERY);

        List<TelemetryIngestPayload> payloads = simulator.tick(NOW);
        int startingBattery = payloadFor(payloads, "BUS-042").batteryPercent();
        assertThat(startingBattery).isLessThan(20);
        assertThat(payloadFor(payloads, "BUS-101").batteryPercent()).isGreaterThan(50);

        IntStream.range(0, 200).forEach(tick -> simulator.tick(NOW));

        assertThat(simulator.fleet().getFirst().batteryPercent()).isLessThan(startingBattery);
        assertThat(simulator.fleet().getFirst().batteryPercent()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void multiIncidentCombinesDeviationDwellAndSilence() {
        FleetSimulator simulator = simulator(ScenarioType.MULTI_INCIDENT);

        List<TelemetryIngestPayload> payloads = simulator.tick(NOW);

        assertThat(distanceToShape(payloadFor(payloads, "BUS-042"))).isGreaterThan(100.0);
        assertThat(payloadFor(payloads, "BUS-101").speedKph()).isZero();
        assertThat(reportingVehicles(payloads)).doesNotContain("BUS-204");
    }

    @Test
    void recoveryRunsSlowlyThenReturnsToNormalSpeed() {
        FleetSimulator simulator = simulator(ScenarioType.RECOVERY);

        double degradedSpeed = simulator.tick(NOW).getFirst().speedKph();

        List<TelemetryIngestPayload> recovered = null;
        for (int tick = 0; tick < 200; tick++) {
            List<TelemetryIngestPayload> payloads = simulator.tick(NOW);
            if (!ScenarioProfile.isRecoveryDegradedPhase(simulator.tickNumber())) {
                recovered = payloads;
                break;
            }
        }

        assertThat(recovered).isNotNull();
        assertThat(recovered.getFirst().speedKph()).isGreaterThan(degradedSpeed);
    }

    private FleetSimulator simulator(ScenarioType scenario) {
        return new FleetSimulator(route, FLEET, scenario, TICK, 42L, "test-run");
    }

    private List<TelemetryIngestPayload> run(FleetSimulator simulator, int ticks) {
        return IntStream.range(0, ticks)
                .boxed()
                .flatMap(tick -> simulator.tick(NOW).stream())
                .toList();
    }

    private List<String> positionsOf(List<TelemetryIngestPayload> payloads) {
        return payloads.stream()
                .map(payload -> "%s:%.7f,%.7f:%.2f".formatted(
                        payload.vehicleId(), payload.latitude(), payload.longitude(), payload.speedKph()))
                .toList();
    }

    private List<String> reportingVehicles(List<TelemetryIngestPayload> payloads) {
        return payloads.stream().map(TelemetryIngestPayload::vehicleId).toList();
    }

    private TelemetryIngestPayload payloadFor(List<TelemetryIngestPayload> payloads, String vehicleId) {
        return payloads.stream()
                .filter(payload -> payload.vehicleId().equals(vehicleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No payload for " + vehicleId));
    }

    /** Forward distance, as a fraction of the shape, from a follower to a leader. */
    private double gapBehind(SimulatedVehicle follower, SimulatedVehicle leader) {
        double gap = leader.routeProgress() - follower.routeProgress();
        return gap < 0 ? gap + 1.0 : gap;
    }

    /** The smallest gap between any two consecutive vehicles. */
    private double tightestGap(FleetSimulator simulator) {
        List<SimulatedVehicle> fleet = simulator.fleet();
        double tightest = 1.0;
        for (SimulatedVehicle follower : fleet) {
            for (SimulatedVehicle leader : fleet) {
                if (follower == leader) {
                    continue;
                }
                double gap = gapBehind(follower, leader);
                if (gap > 0) {
                    tightest = Math.min(tightest, gap);
                }
            }
        }
        return tightest;
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
}
