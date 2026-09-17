package com.metropulse.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metropulse.support.OutboxPipeline;
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

/**
 * Covers the ingest side: history and the outbox.
 *
 * <p>Current state is not written by ingest, so tests that care about it deliver the resulting event
 * through {@link OutboxPipeline} first, the way the real consumer would.
 */
class TelemetryIngestionIntegrationTest extends PostgisIntegrationTest {

    private static final String INGEST_KEY = "test-ingest-key";
    private static final String VEHICLE = "BUS-042";

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private TelemetryQueryService queryService;

    @Autowired
    private OutboxPipeline outboxPipeline;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearTelemetry() {
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void acceptedTelemetryStoresTheObservationAndAnOutboxEventInOneTransaction() {
        Instant recordedAt = Instant.parse("2026-09-16T10:15:00Z");

        var result = ingestionService.ingest(INGEST_KEY, request("evt-1", recordedAt, 33.7012, 73.0188, 38.1, 71));

        assertThat(result.status()).isEqualTo(TelemetryIngestStatus.ACCEPTED);
        assertThat(countTelemetry()).isEqualTo(1);
        assertThat(countOutboxEvents()).isEqualTo(1);
    }

    @Test
    void ingestDoesNotWriteCurrentStateUntilTheEventIsConsumed() {
        ingestionService.ingest(
                INGEST_KEY, request("evt-pending", Instant.parse("2026-09-16T10:15:00Z"), 33.7012, 73.0188, 38.1, 71));

        assertThat(queryService.findLatestVehicleTelemetry()).isEmpty();

        outboxPipeline.drain();

        assertThat(currentStateFor(VEHICLE).sourceEventId()).isEqualTo("evt-pending");
    }

    @Test
    void theOutboxEventCarriesBothTimestampsAndTheObservation() {
        Instant recordedAt = Instant.parse("2026-09-16T10:15:00Z");
        ingestionService.ingest(INGEST_KEY, request("evt-envelope", recordedAt, 33.7012, 73.0188, 38.1, 71));

        JsonNode envelope = readEnvelope(outboxPipeline.lastPayload());

        assertThat(envelope.get("eventType").asText()).isEqualTo("VehicleTelemetryRecorded");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(VEHICLE);
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("sourceEventId").asText()).isEqualTo("evt-envelope");
        assertThat(Instant.parse(payload.get("recordedAt").asText())).isEqualTo(recordedAt);
        assertThat(Instant.parse(payload.get("receivedAt").asText())).isAfterOrEqualTo(recordedAt);
        assertThat(payload.get("latitude").asDouble()).isEqualTo(33.7012);
        assertThat(payload.get("batteryPercent").asInt()).isEqualTo(71);
    }

    @Test
    void consumedTelemetryBecomesCurrentState() {
        Instant recordedAt = Instant.parse("2026-09-16T10:15:00Z");
        ingestionService.ingest(INGEST_KEY, request("evt-state", recordedAt, 33.7012, 73.0188, 38.1, 71));

        outboxPipeline.drain();

        LatestVehicleTelemetry state = currentStateFor(VEHICLE);
        assertThat(state.sourceEventId()).isEqualTo("evt-state");
        assertThat(state.recordedAt()).isEqualTo(recordedAt);
        assertThat(state.latitude()).isEqualByComparingTo("33.7012");
        assertThat(state.longitude()).isEqualByComparingTo("73.0188");
        assertThat(state.batteryPercent()).isEqualTo(71);
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

        outboxPipeline.drain();
        assertThat(currentStateFor(VEHICLE).latitude()).isEqualByComparingTo("33.7012");
    }

    @Test
    void telemetryLocationIsStoredAsAnSrid4326Point() {
        ingestionService.ingest(
                INGEST_KEY, request("evt-geo", Instant.parse("2026-09-16T10:15:00Z"), 33.7012, 73.0188, 38.1, 71));

        String geometryType = jdbcTemplate.queryForObject(
                "SELECT GeometryType(location) FROM vehicle_telemetry", String.class);
        Integer srid = jdbcTemplate.queryForObject(
                "SELECT ST_SRID(location) FROM vehicle_telemetry", Integer.class);

        assertThat(geometryType).isEqualTo("POINT");
        assertThat(srid).isEqualTo(4326);
    }

    @Test
    void historyKeepsEveryObservationIncludingLateArrivals() {
        Instant recent = Instant.parse("2026-09-16T10:15:00Z");
        ingestionService.ingest(INGEST_KEY, request("evt-recent", recent, 33.7100, 73.0200, 21.5, 69));
        var lateArrival = ingestionService.ingest(
                INGEST_KEY, request("evt-stale", recent.minus(5, ChronoUnit.MINUTES), 33.6000, 73.0000, 55.0, 90));

        assertThat(lateArrival.status()).isEqualTo(TelemetryIngestStatus.ACCEPTED);
        assertThat(countTelemetry()).isEqualTo(2);
    }

    @Test
    void unknownVehicleIsRejectedAndStoresNothing() {
        var request = new TelemetryIngestRequest(
                UUID.randomUUID().toString(), "BUS-does-not-exist", Instant.now(),
                33.7012, 73.0188, 10.0, 90.0, 5, 80);

        assertThatThrownBy(() -> ingestionService.ingest(INGEST_KEY, request))
                .isInstanceOf(UnknownVehicleException.class);
        assertThat(countTelemetry()).isZero();
        assertThat(countOutboxEvents()).isZero();
    }

    @Test
    void invalidIngestKeyIsRejectedAndStoresNothing() {
        var request = request("evt-bad-key", Instant.now(), 33.7012, 73.0188, 10.0, 80);

        assertThatThrownBy(() -> ingestionService.ingest("wrong-key", request))
                .isInstanceOf(InvalidIngestKeyException.class);
        assertThat(countTelemetry()).isZero();
        assertThat(countOutboxEvents()).isZero();
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

    private JsonNode readEnvelope(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new AssertionError("Outbox payload is not readable JSON: " + payload, ex);
        }
    }

    private int countTelemetry() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry", Integer.class);
    }

    private int countOutboxEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
    }
}
