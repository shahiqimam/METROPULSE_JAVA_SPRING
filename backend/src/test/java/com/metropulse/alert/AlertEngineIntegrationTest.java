package com.metropulse.alert;

import com.metropulse.alert.application.AlertEngine;
import com.metropulse.alert.application.AlertService;
import com.metropulse.alert.domain.AlertAlreadyClosedException;
import com.metropulse.alert.domain.AlertCloseReason;
import com.metropulse.alert.domain.AlertStatus;
import com.metropulse.alert.domain.AlertType;
import com.metropulse.alert.domain.AlertView;
import com.metropulse.alert.domain.UnknownAlertException;
import com.metropulse.support.MutableClock;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The alert engine against real stored state.
 *
 * <p>Vehicle state is written directly, so each test states the situation it is about rather than
 * arranging telemetry to produce it. Time is driven by {@link MutableClock}, because every rule here
 * is about how long something has been true.
 */
class AlertEngineIntegrationTest extends PostgisIntegrationTest {

    private static final String VEHICLE = "BUS-042";

    @Autowired
    private AlertEngine alertEngine;

    @Autowired
    private AlertService alertService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        clock.reset();
        jdbcTemplate.update("DELETE FROM alert_candidate");
        jdbcTemplate.update("DELETE FROM alert");
        jdbcTemplate.update("DELETE FROM headway_condition");
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
    }

    @Test
    void aConditionIsACandidateBeforeItIsAnAlert() {
        placeVehicle(VEHICLE, 0.5, 300);

        alertEngine.evaluate();

        assertThat(liveAlerts()).isEmpty();
        assertThat(candidateCount()).isEqualTo(1);
    }

    @Test
    void aConditionThatHoldsForItsPersistenceWindowOpensAnAlert() {
        placeVehicle(VEHICLE, 0.5, 300);
        alertEngine.evaluate();

        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.type()).isEqualTo(AlertType.ROUTE_DEVIATION);
            assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
            assertThat(alert.vehicleId()).isEqualTo(VEHICLE);
            assertThat(alert.routeCode()).isEqualTo("M42");
            assertThat(alert.details()).containsEntry("deviationMeters", 300);
        });
        assertThat(candidateCount()).as("the candidate becomes the alert").isZero();
    }

    @Test
    void theAlertRecordsWhenTheConditionStartedNotWhenItMatured() {
        placeVehicle(VEHICLE, 0.5, 300);
        alertEngine.evaluate();
        var conditionStarted = clock.instant();

        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow().plusSeconds(30));
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts().getFirst().openedAt()).isEqualTo(conditionStarted);
    }

    @Test
    void aConditionThatVanishesBeforeMaturingLeavesNoTrace() {
        placeVehicle(VEHICLE, 0.5, 300);
        alertEngine.evaluate();

        clock.advance(Duration.ofSeconds(20));
        placeVehicle(VEHICLE, 0.5, 5);
        alertEngine.evaluate();

        assertThat(liveAlerts()).isEmpty();
        assertThat(candidateCount()).isZero();
        assertThat(allAlerts()).as("nothing reaches the controller's history").isEmpty();
    }

    @Test
    void repeatedEvaluationsOfTheSameConditionProduceOneAlert() {
        openRouteDeviationAlert();

        for (int tick = 0; tick < 10; tick++) {
            clock.advance(Duration.ofSeconds(10));
            refreshTelemetry();
            alertEngine.evaluate();
        }

        assertThat(allAlerts()).hasSize(1);
    }

    @Test
    void anAlertIsNotClosedTheMomentItsConditionStops() {
        openRouteDeviationAlert();

        placeVehicle(VEHICLE, 0.5, 5);
        alertEngine.evaluate();

        AlertView alert = liveAlerts().getFirst();
        assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.recoveringSince()).as("recovery has started").isNotNull();
    }

    @Test
    void anAlertClosesOnceItsConditionHasStayedAwayForTheRecoveryWindow() {
        openRouteDeviationAlert();

        placeVehicle(VEHICLE, 0.5, 5);
        alertEngine.evaluate();

        clock.advance(AlertType.ROUTE_DEVIATION.recoveryWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).isEmpty();
        assertThat(allAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.status()).isEqualTo(AlertStatus.CLOSED);
            assertThat(alert.closeReason()).isEqualTo(AlertCloseReason.RECOVERED);
        });
    }

    @Test
    void aConditionThatReturnsDuringRecoveryKeepsTheSameAlert() {
        openRouteDeviationAlert();

        placeVehicle(VEHICLE, 0.5, 5);
        alertEngine.evaluate();
        assertThat(liveAlerts().getFirst().recoveringSince()).isNotNull();

        clock.advance(Duration.ofSeconds(20));
        placeVehicle(VEHICLE, 0.5, 300);
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.recoveringSince()).as("recovery is cancelled").isNull();
            assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
        });
        assertThat(allAlerts()).hasSize(1);
    }

    @Test
    void aVehicleInsideTheHysteresisBandKeepsItsAlertOpen() {
        openRouteDeviationAlert();

        // 80 m would not open an alert, but it is not inside the clear band either.
        placeVehicle(VEHICLE, 0.5, 80);
        clock.advance(AlertType.ROUTE_DEVIATION.recoveryWindow().plusSeconds(30));
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).hasSize(1);
        assertThat(liveAlerts().getFirst().recoveringSince()).isNull();
    }

    @Test
    void aConditionThatRecursAfterClosingOpensASecondAlert() {
        openRouteDeviationAlert();
        placeVehicle(VEHICLE, 0.5, 5);
        alertEngine.evaluate();
        clock.advance(AlertType.ROUTE_DEVIATION.recoveryWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        placeVehicle(VEHICLE, 0.5, 300);
        refreshTelemetry();
        alertEngine.evaluate();
        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(allAlerts()).as("the same fingerprint can recur as a new alert").hasSize(2);
        assertThat(liveAlerts()).hasSize(1);
    }

    @Test
    void acknowledgingRecordsWhoSawItWithoutClosingIt() {
        openRouteDeviationAlert();
        long alertId = liveAlerts().getFirst().id();

        AlertView acknowledged = alertService.acknowledge(alertId, "controller-a");

        assertThat(acknowledged.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedBy()).isEqualTo("controller-a");
        assertThat(acknowledged.acknowledgedAt()).isNotNull();
        assertThat(liveAlerts()).as("it is still live because the condition is still true").hasSize(1);
    }

    @Test
    void acknowledgingTwiceKeepsWhoSawItFirst() {
        openRouteDeviationAlert();
        long alertId = liveAlerts().getFirst().id();

        alertService.acknowledge(alertId, "controller-a");
        clock.advance(Duration.ofMinutes(5));
        AlertView second = alertService.acknowledge(alertId, "controller-b");

        assertThat(second.acknowledgedBy()).isEqualTo("controller-a");
    }

    @Test
    void anAcknowledgedAlertStillClosesWhenItsConditionRecovers() {
        openRouteDeviationAlert();
        alertService.acknowledge(liveAlerts().getFirst().id(), "controller-a");

        placeVehicle(VEHICLE, 0.5, 5);
        alertEngine.evaluate();
        clock.advance(AlertType.ROUTE_DEVIATION.recoveryWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).isEmpty();
    }

    @Test
    void closingByHandRecordsThatAControllerDidIt() {
        openRouteDeviationAlert();
        long alertId = liveAlerts().getFirst().id();

        AlertView closed = alertService.close(alertId);

        assertThat(closed.status()).isEqualTo(AlertStatus.CLOSED);
        assertThat(closed.closeReason()).isEqualTo(AlertCloseReason.CLOSED_BY_CONTROLLER);
    }

    @Test
    void anAlertClosedByHandCanBeRaisedAgainIfTheConditionPersists() {
        openRouteDeviationAlert();
        alertService.close(liveAlerts().getFirst().id());

        // The vehicle is still off route, so the engine is right to raise it again.
        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow().plusSeconds(1));
        refreshTelemetry();
        alertEngine.evaluate();
        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).hasSize(1);
        assertThat(allAlerts()).hasSize(2);
    }

    @Test
    void actingOnAClosedAlertIsRefused() {
        openRouteDeviationAlert();
        long alertId = liveAlerts().getFirst().id();
        alertService.close(alertId);

        assertThatThrownBy(() -> alertService.acknowledge(alertId, "controller-a"))
                .isInstanceOf(AlertAlreadyClosedException.class);
        assertThatThrownBy(() -> alertService.close(alertId))
                .isInstanceOf(AlertAlreadyClosedException.class);
    }

    @Test
    void actingOnAnAlertThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> alertService.findAlert(999_999L))
                .isInstanceOf(UnknownAlertException.class);
    }

    @Test
    void severalVehiclesWithTheSameProblemGetTheirOwnAlerts() {
        placeVehicle("BUS-042", 0.2, 300);
        placeVehicle("BUS-101", 0.6, 400);
        alertEngine.evaluate();
        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow());
        refreshTelemetry();
        alertEngine.evaluate();

        assertThat(liveAlerts()).hasSize(2);
        assertThat(liveAlerts()).extracting(AlertView::vehicleId).containsExactlyInAnyOrder("BUS-042", "BUS-101");
    }

    @Test
    void theSummaryCountsLiveAlertsBySeverity() {
        openRouteDeviationAlert();

        assertThat(alertService.liveCountsBySeverity())
                .containsEntry("MAJOR", 1)
                .containsEntry("MINOR", 0)
                .containsEntry("CRITICAL", 0);
    }

    /** Puts a vehicle off route and runs the engine until the alert exists. */
    private void openRouteDeviationAlert() {
        placeVehicle(VEHICLE, 0.5, 300);
        alertEngine.evaluate();
        clock.advance(AlertType.ROUTE_DEVIATION.persistenceWindow());
        refreshTelemetry();
        alertEngine.evaluate();
        assertThat(liveAlerts()).hasSize(1);
    }

    private List<AlertView> liveAlerts() {
        return alertService.findAlerts(false, null);
    }

    private List<AlertView> allAlerts() {
        return alertService.findAlerts(true, null);
    }

    private int candidateCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert_candidate", Integer.class);
    }

    private void placeVehicle(String fleetNumber, double routeProgress, double deviationMeters) {
        jdbcTemplate.update("""
                INSERT INTO vehicle_current_state (
                    vehicle_id, source_event_id, recorded_at, received_at, location,
                    speed_kph, heading_degrees, occupancy_estimate, battery_percent,
                    route_id, route_progress, route_deviation_meters
                )
                SELECT v.id, ?, now(), now(), ST_LineInterpolatePoint(r.geometry, ?),
                       25, 90, 20, 80, r.id, ?, ?
                FROM vehicle v
                JOIN route r ON r.code = 'M42'
                WHERE v.fleet_number = ?
                ON CONFLICT (vehicle_id) DO UPDATE
                SET source_event_id = EXCLUDED.source_event_id,
                    recorded_at = EXCLUDED.recorded_at,
                    route_progress = EXCLUDED.route_progress,
                    route_deviation_meters = EXCLUDED.route_deviation_meters
                """,
                fleetNumber + "-" + System.nanoTime(),
                routeProgress,
                routeProgress,
                deviationMeters,
                fleetNumber);
    }

    /** Connectivity is measured by the database clock, so vehicles need keeping fresh as time moves. */
    private void refreshTelemetry() {
        jdbcTemplate.update("UPDATE vehicle_current_state SET recorded_at = now()");
    }
}
