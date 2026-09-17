package com.metropulse.telemetry;

import com.metropulse.support.PostgisIntegrationTest;
import com.metropulse.telemetry.api.TelemetryIngestRequest;
import com.metropulse.telemetry.application.TelemetryIngestionService;
import com.metropulse.telemetry.domain.InvalidIngestKeyException;
import com.metropulse.telemetry.domain.TelemetryIngestStatus;
import com.metropulse.telemetry.domain.UnknownVehicleException;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryIngestionIntegrationTest extends PostgisIntegrationTest {

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
    void acceptedTelemetryStoresObservationCurrentStateAndOutboxEventInOneTransaction() {
        Instant recordedAt = Instant.parse("2026-09-16T10:15:00Z");

        var result = ingestionService.ingest(INGEST_KEY, request("evt-1", recordedAt, 33.7012, 73.0188, 38.1, 71));

        assertThat(result.status()).isEqualTo(TelemetryIngestStatus.ACCEPTED);
        assertThat(countTelemetry()).isEqualTo(1);
        assertThat(countOutboxEvents()).isEqualTo(1);

        LatestVehicleTelemetry state = currentStateFor(VEHICLE);
        assertThat(state.sourceEventId()).isEqualTo("evt-1");
        assertThat(state.recordedAt()).isEqualTo(recordedAt);
        assertThat(state.latitude()).isEqualByComparingTo("33.7012");
        assertThat(state.longitude()).isEqualByComparingTo("73.0188");
        assertThat(state.batteryPercent()).isEqualTo(71);
    }

    @Test
    void newerTelemetryAdvancesCurrentState() {
        Instant first = Instant.parse("2026-09-16T10:15:00Z");
        ingestionService.ingest(INGEST_KEY, request("evt-early", first, 33.7012, 73.0188, 38.1, 71));
        ingestionService.ingest(INGEST_KEY, request("evt-late", first.plusSeconds(30), 33.7100, 73.0200, 21.5, 69));

        LatestVehicleTelemetry state = currentStateFor(VEHICLE);
        assertThat(state.sourceEventId()).isEqualTo("evt-late");
        assertThat(state.recordedAt()).isEqualTo(first.plusSeconds(30));
        assertThat(state.speedKph()).isEqualByComparingTo("21.5");
        assertThat(countTelemetry()).isEqualTo(2);
    }

    @Test
    void outOfOrderTelemetryIsStoredHistoricallyButDoesNotRewindCurrentState() {
        Instant recent = Instant.parse("2026-09-16T10:15:00Z");
        Instant stale = recent.minus(5, ChronoUnit.MINUTES);

        ingestionService.ingest(INGEST_KEY, request("evt-recent", recent, 33.7100, 73.0200, 21.5, 69));
        var lateArrival = ingestionService.ingest(INGEST_KEY, request("evt-stale", stale, 33.6000, 73.0000, 55.0, 90));

        assertThat(lateArrival.status()).isEqualTo(TelemetryIngestStatus.ACCEPTED);
        assertThat(countTelemetry()).isEqualTo(2);

        LatestVehicleTelemetry state = currentStateFor(VEHICLE);
        assertThat(state.sourceEventId()).isEqualTo("evt-recent");
        assertThat(state.recordedAt()).isEqualTo(recent);
        assertThat(state.latitude()).isEqualByComparingTo("33.7100");
        assertThat(state.batteryPercent()).isEqualTo(69);
    }

    @Test
    void duplicateSourceEventIdIsANoOp() {
        Instant recordedAt = Instant.parse("2026-09-16T10:15:00Z");
        ingestionService.ingest(INGEST_KEY, request("evt-dup", recordedAt, 33.7012, 73.0188, 38.1, 71));

        var duplicate = ingestionService.ingest(
                INGEST_KEY, request("evt-dup", recordedAt.plusSeconds(60), 33.9999, 73.9999, 10.0, 10));

        assertThat(duplicate.status()).isEqualTo(TelemetryIngestStatus.DUPLICATE);
        assertThat(countTelemetry()).isEqualTo(1);
        assertThat(countOutboxEvents()).isEqualTo(1);
        assertThat(currentStateFor(VEHICLE).latitude()).isEqualByComparingTo("33.7012");
    }

    @Test
    void telemetryLocationIsStoredAsAnSrid4326Point() {
        ingestionService.ingest(
                INGEST_KEY, request("evt-geo", Instant.parse("2026-09-16T10:15:00Z"), 33.7012, 73.0188, 38.1, 71));

        String geometryType = jdbcTemplate.queryForObject(
                "SELECT GeometryType(location) FROM vehicle_current_state", String.class);
        Integer srid = jdbcTemplate.queryForObject(
                "SELECT ST_SRID(location) FROM vehicle_current_state", Integer.class);

        assertThat(geometryType).isEqualTo("POINT");
        assertThat(srid).isEqualTo(4326);
    }

    @Test
    void unknownVehicleIsRejectedAndStoresNothing() {
        var request = new TelemetryIngestRequest(
                UUID.randomUUID().toString(), "BUS-does-not-exist", Instant.now(),
                33.7012, 73.0188, 10.0, 90.0, 5, 80);

        assertThatThrownBy(() -> ingestionService.ingest(INGEST_KEY, request))
                .isInstanceOf(UnknownVehicleException.class);
        assertThat(countTelemetry()).isZero();
    }

    @Test
    void invalidIngestKeyIsRejectedAndStoresNothing() {
        var request = request("evt-bad-key", Instant.now(), 33.7012, 73.0188, 10.0, 80);

        assertThatThrownBy(() -> ingestionService.ingest("wrong-key", request))
                .isInstanceOf(InvalidIngestKeyException.class);
        assertThat(countTelemetry()).isZero();
    }

    private TelemetryIngestRequest request(
            String sourceEventId,
            Instant recordedAt,
            double latitude,
            double longitude,
            double speedKph,
            Integer batteryPercent
    ) {
        return new TelemetryIngestRequest(
                sourceEventId, VEHICLE, recordedAt, latitude, longitude, speedKph, 180.0, 42, batteryPercent);
    }

    private LatestVehicleTelemetry currentStateFor(String fleetNumber) {
        return queryService.findLatestVehicleTelemetry().stream()
                .filter(state -> state.vehicleId().equals(fleetNumber))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No current state for " + fleetNumber));
    }

    private int countTelemetry() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry", Integer.class);
    }

    private int countOutboxEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
    }
}
