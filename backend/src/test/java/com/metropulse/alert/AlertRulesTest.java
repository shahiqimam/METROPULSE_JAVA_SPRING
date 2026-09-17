package com.metropulse.alert;

import com.metropulse.alert.application.AlertRules;
import com.metropulse.alert.domain.AlertSignal;
import com.metropulse.alert.domain.AlertType;
import com.metropulse.operations.domain.ConnectivityState;
import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayConditionType;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AlertRulesTest {

    private static final Set<String> NOTHING_LIVE = Set.of();

    @Test
    void aNominalVehicleRaisesNothing() {
        assertThat(evaluate(vehicle().build())).isEmpty();
    }

    @Test
    void aVehicleBeyondTheDeviationThresholdRaisesRouteDeviation() {
        List<AlertSignal> signals = evaluate(vehicle().deviationMeters(180).build());

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(AlertType.ROUTE_DEVIATION);
            assertThat(signal.vehicleId()).isEqualTo("BUS-042");
            assertThat(signal.details()).containsEntry("deviationMeters", 180L);
        });
    }

    @Test
    void aVehicleExactlyAtTheDeviationThresholdRaisesNothing() {
        assertThat(evaluate(vehicle().deviationMeters(AlertRules.ROUTE_DEVIATION_OPEN_METERS).build())).isEmpty();
    }

    @Test
    void anAlertingVehicleKeepsAlertingInsideTheHysteresisBand() {
        String fingerprint = AlertType.ROUTE_DEVIATION + "|BUS-042";
        // 80 m is below the 100 m opening threshold but above the 60 m clearing one.
        LatestVehicleTelemetry vehicle = vehicle().deviationMeters(80).build();

        assertThat(evaluate(vehicle)).isEmpty();
        assertThat(AlertRules.evaluate(List.of(vehicle), List.of(), Set.of(fingerprint)))
                .as("an open alert should not clear until the vehicle is back inside the clear band")
                .extracting(AlertSignal::type)
                .containsExactly(AlertType.ROUTE_DEVIATION);
    }

    @Test
    void anAlertingVehicleStopsAlertingBelowTheClearThreshold() {
        String fingerprint = AlertType.ROUTE_DEVIATION + "|BUS-042";
        LatestVehicleTelemetry vehicle = vehicle().deviationMeters(55).build();

        assertThat(AlertRules.evaluate(List.of(vehicle), List.of(), Set.of(fingerprint))).isEmpty();
    }

    @Test
    void anOfflineVehicleRaisesTelemetryOfflineAndNotRouteDeviation() {
        List<AlertSignal> signals = evaluate(vehicle()
                .connectivity(ConnectivityState.OFFLINE)
                .telemetryAgeSeconds(300)
                .deviationMeters(500)
                .build());

        // The stored distance describes where it was, not where it is.
        assertThat(signals).extracting(AlertSignal::type).containsExactly(AlertType.TELEMETRY_OFFLINE);
    }

    @Test
    void aStaleVehicleDoesNotRaiseAnything() {
        assertThat(evaluate(vehicle().connectivity(ConnectivityState.STALE).telemetryAgeSeconds(30).build()))
                .isEmpty();
    }

    @Test
    void lowBatteryOpensAtTheThresholdAndClearsOnlyAboveTheHigherOne() {
        String fingerprint = AlertType.LOW_BATTERY + "|BUS-042";

        assertThat(evaluate(vehicle().batteryPercent(AlertRules.LOW_BATTERY_OPEN_PERCENT).build()))
                .extracting(AlertSignal::type).containsExactly(AlertType.LOW_BATTERY);
        assertThat(evaluate(vehicle().batteryPercent(AlertRules.LOW_BATTERY_OPEN_PERCENT + 1).build())).isEmpty();

        LatestVehicleTelemetry charging = vehicle().batteryPercent(25).build();
        assertThat(AlertRules.evaluate(List.of(charging), List.of(), Set.of(fingerprint)))
                .as("still alerting at 25% because the clear threshold is 30%")
                .hasSize(1);
        assertThat(AlertRules.evaluate(List.of(vehicle().batteryPercent(31).build()), List.of(), Set.of(fingerprint)))
                .isEmpty();
    }

    @Test
    void aVehicleWithoutABatteryIsNeverLowOnCharge() {
        assertThat(evaluate(vehicle().batteryPercent(null).build())).isEmpty();
    }

    @Test
    void overCapacityOpensWhenLoadReachesCapacity() {
        assertThat(evaluate(vehicle().occupancy(80).capacity(80).build()))
                .extracting(AlertSignal::type).containsExactly(AlertType.OVER_CAPACITY);
        assertThat(evaluate(vehicle().occupancy(79).capacity(80).build())).isEmpty();
    }

    @Test
    void aVehicleWellBehindItsScheduleRaisesVehicleLate() {
        List<AlertSignal> signals = evaluate(vehicle().scheduleDeviationSeconds(400).build());

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(AlertType.VEHICLE_LATE);
            assertThat(signal.details()).containsEntry("scheduleDeviationSeconds", 400);
        });
    }

    @Test
    void aVehicleRunningAheadOfItsScheduleRaisesVehicleEarly() {
        // Early is treated as its own problem: a passenger who arrives on time for a bus that has
        // already gone waits a full headway.
        assertThat(evaluate(vehicle().scheduleDeviationSeconds(-120).build()))
                .extracting(AlertSignal::type)
                .containsExactly(AlertType.VEHICLE_EARLY);
    }

    @Test
    void theScheduleTolerancesAreAsymmetric() {
        // Five minutes late is acceptable; ninety seconds early is not.
        assertThat(evaluate(vehicle().scheduleDeviationSeconds(AlertRules.LATE_OPEN_SECONDS - 1).build())).isEmpty();
        assertThat(evaluate(vehicle().scheduleDeviationSeconds(AlertRules.EARLY_OPEN_SECONDS + 1).build())).isEmpty();
        assertThat(evaluate(vehicle().scheduleDeviationSeconds(AlertRules.LATE_OPEN_SECONDS).build())).hasSize(1);
        assertThat(evaluate(vehicle().scheduleDeviationSeconds(AlertRules.EARLY_OPEN_SECONDS).build())).hasSize(1);
    }

    @Test
    void aLateVehicleKeepsItsAlertUntilItIsWellBackOnSchedule() {
        String fingerprint = AlertType.VEHICLE_LATE + "|BUS-042";
        LatestVehicleTelemetry recovering = vehicle().scheduleDeviationSeconds(240).build();

        assertThat(evaluate(recovering)).as("240s alone would not open an alert").isEmpty();
        assertThat(AlertRules.evaluate(List.of(recovering), List.of(), Set.of(fingerprint)))
                .as("but it is not yet inside the clear band")
                .hasSize(1);
    }

    @Test
    void aVehicleWithNoMeasuredDeviationRaisesNothing() {
        // Before its first call there is nothing to measure against, and guessing would be worse.
        assertThat(evaluate(vehicle().scheduleDeviationSeconds(null).build())).isEmpty();
    }

    @Test
    void anOfflineVehicleIsNotJudgedAgainstItsSchedule() {
        List<AlertSignal> signals = evaluate(vehicle()
                .connectivity(ConnectivityState.OFFLINE)
                .telemetryAgeSeconds(300)
                .scheduleDeviationSeconds(900)
                .build());

        assertThat(signals).extracting(AlertSignal::type).containsExactly(AlertType.TELEMETRY_OFFLINE);
    }

    @Test
    void confirmedHeadwayConditionsBecomeAlerts() {
        List<AlertSignal> signals = AlertRules.evaluate(
                List.of(),
                List.of(headwayCondition(HeadwayConditionType.BUNCHING, true)),
                NOTHING_LIVE);

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(AlertType.BUNCHING);
            assertThat(signal.fingerprint()).isEqualTo("BUNCHING|M42|BUS-042|BUS-101");
            assertThat(signal.details()).containsEntry("leaderVehicleId", "BUS-042");
            assertThat(signal.details()).containsEntry("followerVehicleId", "BUS-101");
        });
    }

    @Test
    void headwayConditionsStillBeingWatchedDoNotBecomeAlerts() {
        assertThat(AlertRules.evaluate(
                List.of(),
                List.of(headwayCondition(HeadwayConditionType.BUNCHING, false)),
                NOTHING_LIVE)).isEmpty();
    }

    @Test
    void anExcessiveGapConditionBecomesItsOwnAlertType() {
        assertThat(AlertRules.evaluate(
                List.of(),
                List.of(headwayCondition(HeadwayConditionType.EXCESSIVE_GAP, true)),
                NOTHING_LIVE))
                .extracting(AlertSignal::type)
                .containsExactly(AlertType.EXCESSIVE_GAP);
    }

    @Test
    void oneVehicleCanRaiseSeveralDifferentConditionsAtOnce() {
        List<AlertSignal> signals = evaluate(vehicle()
                .deviationMeters(300)
                .batteryPercent(8)
                .occupancy(95)
                .capacity(80)
                .build());

        assertThat(signals).extracting(AlertSignal::type)
                .containsExactlyInAnyOrder(
                        AlertType.ROUTE_DEVIATION, AlertType.LOW_BATTERY, AlertType.OVER_CAPACITY);
        assertThat(signals).extracting(AlertSignal::fingerprint).doesNotHaveDuplicates();
    }

    private List<AlertSignal> evaluate(LatestVehicleTelemetry vehicle) {
        return AlertRules.evaluate(List.of(vehicle), List.of(), NOTHING_LIVE);
    }

    private HeadwayCondition headwayCondition(HeadwayConditionType type, boolean confirmed) {
        return new HeadwayCondition(
                type + "|M42|BUS-042|BUS-101",
                "M42",
                type,
                "BUS-042",
                "BUS-101",
                12.0,
                55,
                Instant.parse("2026-09-17T09:00:00Z"),
                Duration.ofSeconds(120),
                confirmed);
    }

    private VehicleBuilder vehicle() {
        return new VehicleBuilder();
    }

    /** Builds a nominal vehicle that raises nothing, so each test changes only what it is about. */
    private static final class VehicleBuilder {
        private ConnectivityState connectivity = ConnectivityState.ONLINE;
        private double telemetryAgeSeconds = 2;
        private double deviationMeters = 5;
        private Integer batteryPercent = 80;
        private int occupancy = 20;
        private int capacity = 80;
        private Integer scheduleDeviationSeconds = 0;

        VehicleBuilder connectivity(ConnectivityState connectivity) {
            this.connectivity = connectivity;
            return this;
        }

        VehicleBuilder telemetryAgeSeconds(double telemetryAgeSeconds) {
            this.telemetryAgeSeconds = telemetryAgeSeconds;
            return this;
        }

        VehicleBuilder deviationMeters(double deviationMeters) {
            this.deviationMeters = deviationMeters;
            return this;
        }

        VehicleBuilder batteryPercent(Integer batteryPercent) {
            this.batteryPercent = batteryPercent;
            return this;
        }

        VehicleBuilder occupancy(int occupancy) {
            this.occupancy = occupancy;
            return this;
        }

        VehicleBuilder capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        VehicleBuilder scheduleDeviationSeconds(Integer scheduleDeviationSeconds) {
            this.scheduleDeviationSeconds = scheduleDeviationSeconds;
            return this;
        }

        LatestVehicleTelemetry build() {
            return new LatestVehicleTelemetry(
                    "BUS-042",
                    "STANDARD_BUS",
                    "BATTERY_ELECTRIC",
                    capacity,
                    "ACTIVE",
                    "evt-1",
                    Instant.parse("2026-09-17T09:00:00Z"),
                    Instant.parse("2026-09-17T09:00:01Z"),
                    new BigDecimal("40.7152"),
                    new BigDecimal("-73.9980"),
                    new BigDecimal("24.0"),
                    new BigDecimal("90.0"),
                    occupancy,
                    batteryPercent,
                    "M42",
                    new BigDecimal("0.5"),
                    BigDecimal.valueOf(deviationMeters),
                    "M42-WKD-0700-EAST",
                    scheduleDeviationSeconds,
                    "Central Library",
                    telemetryAgeSeconds,
                    connectivity);
        }
    }
}
