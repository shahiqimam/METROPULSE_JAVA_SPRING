package com.metropulse.telemetry;

import com.metropulse.operations.domain.ConnectivityState;
import com.metropulse.support.PostgisIntegrationTest;
import com.metropulse.telemetry.api.TelemetryIngestRequest;
import com.metropulse.telemetry.application.TelemetryIngestionService;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the PostGIS route projection against the seeded M42 route, whose shape runs west to east
 * from (-74.0060, 40.7128) to (-73.9900, 40.7178).
 */
class VehicleRouteProjectionIntegrationTest extends PostgisIntegrationTest {

    private static final String INGEST_KEY = "test-ingest-key";
    private static final String VEHICLE = "BUS-042";

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private TelemetryQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTelemetry() {
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void vehicleAtTheStartOfTheRouteHasZeroProgressAndNoDeviation() {
        ingest("evt-start", Instant.now(), 40.7128, -74.0060);

        LatestVehicleTelemetry state = stateFor(VEHICLE);
        assertThat(state.routeCode()).isEqualTo("M42");
        assertThat(state.routeProgress()).isEqualByComparingTo("0");
        assertThat(state.routeDeviationMeters().doubleValue()).isCloseTo(0.0, within(1.0));
    }

    @Test
    void vehicleAtTheEndOfTheRouteHasFullProgress() {
        ingest("evt-end", Instant.now(), 40.7178, -73.9900);

        assertThat(stateFor(VEHICLE).routeProgress()).isEqualByComparingTo("1");
    }

    @Test
    void vehicleOnTheMiddleOfTheRouteHasProgressBetweenTheEndpoints() {
        ingest("evt-middle", Instant.now(), 40.7152, -73.9980);

        LatestVehicleTelemetry state = stateFor(VEHICLE);
        assertThat(state.routeProgress().doubleValue()).isStrictlyBetween(0.3, 0.7);
        assertThat(state.routeDeviationMeters().doubleValue()).isLessThan(5.0);
    }

    @Test
    void vehicleOffTheRouteShapeReportsDeviationInMeters() {
        // Roughly 550 m south of the route line at the same longitude as the third stop.
        ingest("evt-off-route", Instant.now(), 40.7102, -73.9980);

        LatestVehicleTelemetry state = stateFor(VEHICLE);
        assertThat(state.routeDeviationMeters().doubleValue()).isBetween(400.0, 700.0);
        assertThat(state.routeProgress()).isNotNull();
    }

    @Test
    void unassignedVehicleHasNoRouteProjection() {
        jdbcTemplate.update("UPDATE vehicle SET assigned_route_id = NULL WHERE fleet_number = ?", "BUS-317");
        try {
            ingestFor("BUS-317", "evt-unassigned", Instant.now(), 40.7152, -73.9980);

            LatestVehicleTelemetry state = stateFor("BUS-317");
            assertThat(state.routeCode()).isNull();
            assertThat(state.routeProgress()).isNull();
            assertThat(state.routeDeviationMeters()).isNull();
        } finally {
            jdbcTemplate.update("""
                    UPDATE vehicle
                    SET assigned_route_id = (SELECT id FROM route WHERE code = 'M42')
                    WHERE fleet_number = ?
                    """, "BUS-317");
        }
    }

    @Test
    void freshTelemetryIsOnlineAndOldTelemetryIsOffline() {
        ingest("evt-fresh", Instant.now(), 40.7152, -73.9980);
        assertThat(stateFor(VEHICLE).connectivityState()).isEqualTo(ConnectivityState.ONLINE);

        ingestFor("BUS-101", "evt-stale", Instant.now().minus(10, ChronoUnit.MINUTES), 40.7152, -73.9980);
        LatestVehicleTelemetry stale = stateFor("BUS-101");
        assertThat(stale.connectivityState()).isEqualTo(ConnectivityState.OFFLINE);
        assertThat(stale.telemetryAgeSeconds()).isGreaterThan(ConnectivityState.STALE_MAX_AGE_SECONDS);
    }

    private void ingest(String sourceEventId, Instant recordedAt, double latitude, double longitude) {
        ingestFor(VEHICLE, sourceEventId, recordedAt, latitude, longitude);
    }

    private void ingestFor(
            String fleetNumber,
            String sourceEventId,
            Instant recordedAt,
            double latitude,
            double longitude
    ) {
        ingestionService.ingest(INGEST_KEY, new TelemetryIngestRequest(
                sourceEventId, fleetNumber, recordedAt, latitude, longitude, 24.0, 90.0, 30, 78));
    }

    private LatestVehicleTelemetry stateFor(String fleetNumber) {
        return queryService.findLatestVehicleTelemetry().stream()
                .filter(state -> state.vehicleId().equals(fleetNumber))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No current state for " + fleetNumber));
    }
}
