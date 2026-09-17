package com.metropulse.operations;

import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayConditionType;
import com.metropulse.operations.headway.HeadwayEvaluator;
import com.metropulse.operations.headway.HeadwayEvaluator.RouteContext;
import com.metropulse.operations.headway.HeadwayQueryService;
import com.metropulse.operations.headway.HeadwayRule;
import com.metropulse.operations.headway.RouteHeadwaySnapshot;
import com.metropulse.support.MutableClock;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headway rules against real stored state.
 *
 * <p>Time is driven by a {@link MutableClock} rather than by waiting, so the persistence window is
 * tested at its boundary instead of approximately.
 *
 * <p>Vehicle positions are written straight into current state. That skips ingest deliberately: what
 * is under test is what the rules do with a given arrangement of vehicles, not how it got there.
 */
class HeadwayEvaluatorIntegrationTest extends PostgisIntegrationTest {

    private static final String ROUTE = "M42";

    @Autowired
    private HeadwayEvaluator headwayEvaluator;

    @Autowired
    private HeadwayQueryService headwayQueryService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        clock.reset();
        jdbcTemplate.update("DELETE FROM headway_condition");
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void theSeededRouteHasALengthAndATargetHeadway() {
        RouteContext route = route();

        assertThat(route.targetHeadwaySeconds()).isEqualTo(55);
        assertThat(route.lengthMeters()).isBetween(1_400.0, 1_800.0);
    }

    @Test
    void evenlySpacedVehiclesRaiseNoConditions() {
        spaceFleetEvenly();

        assertThat(headwayEvaluator.evaluateRoute(route())).isEmpty();
        assertThat(conditionCount()).isZero();
    }

    @Test
    void aClosedUpPairIsWatchedBeforeItIsConfirmed() {
        bunchTwoVehicles();

        List<HeadwayCondition> conditions = headwayEvaluator.evaluateRoute(route());

        assertThat(conditions).hasSize(1);
        HeadwayCondition condition = conditions.getFirst();
        assertThat(condition.type()).isEqualTo(HeadwayConditionType.BUNCHING);
        assertThat(condition.followerVehicleId()).isEqualTo("BUS-101");
        assertThat(condition.leaderVehicleId()).isEqualTo("BUS-042");
        assertThat(condition.confirmed()).as("not yet held long enough").isFalse();
        assertThat(condition.ratioToTarget()).isLessThan(HeadwayRule.BUNCHING_RATIO);
    }

    @Test
    void aConditionNeedsTwoObservationsSpanningTheWindow() {
        bunchTwoVehicles();

        // Time passing before the condition was ever observed proves nothing about it.
        clock.advance(HeadwayRule.PERSISTENCE_WINDOW.multipliedBy(3));
        refreshTelemetryTimestamps();

        assertThat(headwayEvaluator.evaluateRoute(route()).getFirst().confirmed()).isFalse();
    }

    @Test
    void aConditionIsConfirmedOnceItHasHeldForThePersistenceWindow() {
        bunchTwoVehicles();
        headwayEvaluator.evaluateRoute(route());

        clock.advance(HeadwayRule.PERSISTENCE_WINDOW.minusSeconds(1));
        refreshTelemetryTimestamps();
        assertThat(headwayEvaluator.evaluateRoute(route()).getFirst().confirmed())
                .as("one second short of the window").isFalse();

        clock.advance(Duration.ofSeconds(1));
        refreshTelemetryTimestamps();
        assertThat(headwayEvaluator.evaluateRoute(route()).getFirst().confirmed())
                .as("exactly at the window").isTrue();
    }

    @Test
    void aConfirmedConditionStaysConfirmedWhileItPersists() {
        bunchTwoVehicles();
        headwayEvaluator.evaluateRoute(route());
        clock.advance(HeadwayRule.PERSISTENCE_WINDOW);
        refreshTelemetryTimestamps();
        headwayEvaluator.evaluateRoute(route());

        clock.advance(Duration.ofMinutes(5));
        refreshTelemetryTimestamps();

        HeadwayCondition condition = headwayEvaluator.evaluateRoute(route()).getFirst();
        assertThat(condition.confirmed()).isTrue();
        assertThat(condition.observedForSeconds()).isGreaterThanOrEqualTo(390);
    }

    @Test
    void recoveredSpacingClearsTheCondition() {
        bunchTwoVehicles();
        // The spell has to be opened before time can pass over it.
        headwayEvaluator.evaluateRoute(route());
        clock.advance(HeadwayRule.PERSISTENCE_WINDOW);
        refreshTelemetryTimestamps();
        assertThat(headwayEvaluator.evaluateRoute(route()).getFirst().confirmed()).isTrue();

        spaceFleetEvenly();

        assertThat(headwayEvaluator.evaluateRoute(route())).isEmpty();
        assertThat(conditionCount()).isZero();
    }

    @Test
    void aPairThatDeterioratesAgainStartsItsWindowFromScratch() {
        bunchTwoVehicles();
        headwayEvaluator.evaluateRoute(route());
        clock.advance(HeadwayRule.PERSISTENCE_WINDOW);
        refreshTelemetryTimestamps();
        headwayEvaluator.evaluateRoute(route());

        spaceFleetEvenly();
        headwayEvaluator.evaluateRoute(route());

        bunchTwoVehicles();
        assertThat(headwayEvaluator.evaluateRoute(route()).getFirst().confirmed())
                .as("recovery resets the spell; it must earn confirmation again").isFalse();
    }

    @Test
    void aWideGapIsClassifiedAsAnExcessiveGap() {
        // Three vehicles bunched together and one far behind them.
        placeVehicle("BUS-042", 0.50, 28.0);
        placeVehicle("BUS-101", 0.55, 28.0);
        placeVehicle("BUS-204", 0.60, 28.0);
        placeVehicle("BUS-317", 0.95, 28.0);

        List<HeadwayCondition> conditions = headwayEvaluator.evaluateRoute(route());

        assertThat(conditions)
                .anySatisfy(condition -> assertThat(condition.type()).isEqualTo(HeadwayConditionType.EXCESSIVE_GAP));
        assertThat(conditions)
                .anySatisfy(condition -> assertThat(condition.type()).isEqualTo(HeadwayConditionType.BUNCHING));
    }

    @Test
    void vehiclesWithStaleTelemetryAreLeftOutOfTheCalculation() {
        bunchTwoVehicles();
        // Age both vehicles past the freshness cut-off.
        jdbcTemplate.update("UPDATE vehicle_current_state SET recorded_at = now() - INTERVAL '10 minutes'");

        assertThat(headwayEvaluator.evaluateRoute(route())).isEmpty();

        RouteHeadwaySnapshot snapshot = headwayQueryService.findRouteHeadway(ROUTE).orElseThrow();
        assertThat(snapshot.vehiclesConsidered()).isZero();
        assertThat(snapshot.pairs()).isEmpty();
    }

    @Test
    void theSnapshotReportsEveryPairWithItsClassification() {
        bunchTwoVehicles();
        headwayEvaluator.evaluateRoute(route());

        RouteHeadwaySnapshot snapshot = headwayQueryService.findRouteHeadway(ROUTE).orElseThrow();

        assertThat(snapshot.routeCode()).isEqualTo(ROUTE);
        assertThat(snapshot.targetHeadwaySeconds()).isEqualTo(55);
        assertThat(snapshot.vehiclesConsidered()).isEqualTo(4);
        assertThat(snapshot.pairs()).hasSize(4);
        assertThat(snapshot.pairs())
                .extracting(RouteHeadwaySnapshot.HeadwayPairView::classification)
                .contains("BUNCHING", "NOMINAL");
        assertThat(snapshot.conditions()).hasSize(1);
    }

    @Test
    void conditionsCanBeReadAcrossEveryRoute() {
        bunchTwoVehicles();
        headwayEvaluator.evaluateRoute(route());

        // The unfiltered read is what the alert engine and the conditions endpoint use.
        assertThat(headwayQueryService.findConditions()).hasSize(1);
    }

    @Test
    void anUnknownRouteHasNoSnapshot() {
        assertThat(headwayQueryService.findRouteHeadway("NOPE")).isEmpty();
    }

    /** The whole fleet at nominal spacing: a quarter of the shape apart. */
    private void spaceFleetEvenly() {
        placeVehicle("BUS-042", 0.00, 28.0);
        placeVehicle("BUS-101", 0.25, 28.0);
        placeVehicle("BUS-204", 0.50, 28.0);
        placeVehicle("BUS-317", 0.75, 28.0);
    }

    /** Puts BUS-101 right behind BUS-042, with the other two evenly spaced away from them. */
    private void bunchTwoVehicles() {
        placeVehicle("BUS-042", 0.52, 28.0);
        placeVehicle("BUS-101", 0.50, 28.0);
        placeVehicle("BUS-204", 0.80, 28.0);
        placeVehicle("BUS-317", 0.20, 28.0);
    }

    private RouteContext route() {
        return headwayEvaluator.findRoute(ROUTE).orElseThrow();
    }

    private void placeVehicle(String fleetNumber, double routeProgress, double speedKph) {
        jdbcTemplate.update("""
                INSERT INTO vehicle_current_state (
                    vehicle_id, source_event_id, recorded_at, received_at, location,
                    speed_kph, heading_degrees, occupancy_estimate, battery_percent,
                    route_id, route_progress, route_deviation_meters
                )
                SELECT
                    v.id,
                    ?,
                    now(),
                    now(),
                    ST_LineInterpolatePoint(r.geometry, ?),
                    ?, 90, 20, 80,
                    r.id,
                    ?,
                    0
                FROM vehicle v
                JOIN route r ON r.code = ?
                WHERE v.fleet_number = ?
                ON CONFLICT (vehicle_id) DO UPDATE
                SET source_event_id = EXCLUDED.source_event_id,
                    recorded_at = EXCLUDED.recorded_at,
                    received_at = EXCLUDED.received_at,
                    location = EXCLUDED.location,
                    speed_kph = EXCLUDED.speed_kph,
                    route_id = EXCLUDED.route_id,
                    route_progress = EXCLUDED.route_progress
                """,
                fleetNumber + "-" + routeProgress + "-" + System.nanoTime(),
                routeProgress,
                speedKph,
                routeProgress,
                ROUTE,
                fleetNumber);
    }

    /** Keeps vehicles "fresh" as test time advances, since freshness is measured by the database clock. */
    private void refreshTelemetryTimestamps() {
        jdbcTemplate.update("UPDATE vehicle_current_state SET recorded_at = now()");
    }

    private int conditionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM headway_condition", Integer.class);
    }
}
