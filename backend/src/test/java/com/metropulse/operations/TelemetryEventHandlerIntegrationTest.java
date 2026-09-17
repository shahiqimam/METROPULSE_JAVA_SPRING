package com.metropulse.operations;

import com.metropulse.operations.consumer.MalformedEventException;
import com.metropulse.operations.consumer.TelemetryEventHandler;
import com.metropulse.operations.consumer.TelemetryEventHandler.EventOutcome;
import com.metropulse.operations.projection.UnknownVehicleInEventException;
import com.metropulse.support.OutboxPipeline;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the consumer side: idempotency under at-least-once delivery, the no-rewind rule, and what
 * happens to events that can never be applied.
 *
 * <p>These tests drive the handler directly rather than through a broker. That proves the handler's
 * own behaviour; it does not prove Kafka delivery, offsets or dead-lettering.
 */
class TelemetryEventHandlerIntegrationTest extends PostgisIntegrationTest {

    private static final String INGEST_KEY = "test-ingest-key";
    private static final String VEHICLE = "BUS-042";

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private TelemetryEventHandler eventHandler;

    @Autowired
    private OutboxPipeline outboxPipeline;

    @Autowired
    private TelemetryQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTelemetry() {
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void anEventAppliedOnceRecordsAClaimAndBecomesCurrentState() {
        ingest("evt-1", Instant.parse("2026-09-16T10:15:00Z"), 40.7152, -73.9980);

        assertThat(outboxPipeline.drain()).containsExactly(EventOutcome.APPLIED);
        assertThat(processedEventCount()).isEqualTo(1);
        assertThat(currentStateFor(VEHICLE).sourceEventId()).isEqualTo("evt-1");
    }

    @Test
    void redeliveryOfTheSameEventIsANoOp() {
        ingest("evt-redelivered", Instant.parse("2026-09-16T10:15:00Z"), 40.7152, -73.9980);
        String envelope = outboxPipeline.lastPayload();

        assertThat(eventHandler.handle(envelope)).isEqualTo(EventOutcome.APPLIED);
        assertThat(eventHandler.handle(envelope)).isEqualTo(EventOutcome.DUPLICATE);
        assertThat(eventHandler.handle(envelope)).isEqualTo(EventOutcome.DUPLICATE);

        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void redeliveredEventDoesNotOverwriteNewerState() {
        ingest("evt-first", Instant.parse("2026-09-16T10:15:00Z"), 40.7128, -74.0060);
        String firstEnvelope = outboxPipeline.lastPayload();
        outboxPipeline.drain();

        ingest("evt-second", Instant.parse("2026-09-16T10:16:00Z"), 40.7178, -73.9900);
        outboxPipeline.drain();

        assertThat(outboxPipeline.redeliver(firstEnvelope)).isEqualTo(EventOutcome.DUPLICATE);
        assertThat(currentStateFor(VEHICLE).sourceEventId()).isEqualTo("evt-second");
    }

    @Test
    void anEventThatArrivesOutOfOrderIsKeptAsHistoryOnly() {
        Instant recent = Instant.parse("2026-09-16T10:15:00Z");
        ingest("evt-recent", recent, 40.7178, -73.9900);
        ingest("evt-stale", recent.minus(5, ChronoUnit.MINUTES), 40.7128, -74.0060);

        // The stale event is published second, so the consumer sees it after the newer one.
        assertThat(outboxPipeline.drain())
                .containsExactly(EventOutcome.APPLIED, EventOutcome.SUPERSEDED);

        LatestVehicleTelemetry state = currentStateFor(VEHICLE);
        assertThat(state.sourceEventId()).isEqualTo("evt-recent");
        assertThat(state.routeProgress()).isEqualByComparingTo("1");
        assertThat(telemetryCount()).isEqualTo(2);
    }

    @Test
    void aSupersededEventIsStillMarkedProcessedSoItIsNotRetriedForever() {
        Instant recent = Instant.parse("2026-09-16T10:15:00Z");
        ingest("evt-newer", recent, 40.7178, -73.9900);
        ingest("evt-older", recent.minusSeconds(120), 40.7128, -74.0060);

        outboxPipeline.drain();

        assertThat(processedEventCount()).isEqualTo(2);
    }

    @Test
    void anEventForAnUnknownVehicleIsTreatedAsAPoisonMessage() {
        String envelope = envelope(UUID.randomUUID(), "VehicleTelemetryRecorded", """
                {
                  "sourceEventId": "evt-ghost",
                  "vehicleId": "BUS-does-not-exist",
                  "recordedAt": "2026-09-16T10:15:00Z",
                  "receivedAt": "2026-09-16T10:15:01Z",
                  "latitude": 40.7152,
                  "longitude": -73.9980,
                  "speedKph": 20.0,
                  "headingDegrees": 90.0,
                  "occupancyEstimate": 10,
                  "batteryPercent": 80
                }
                """);

        assertThatThrownBy(() -> eventHandler.handle(envelope))
                .isInstanceOf(UnknownVehicleInEventException.class);
    }

    @Test
    void anEnvelopeThatIsNotReadableIsTreatedAsAPoisonMessage() {
        assertThatThrownBy(() -> eventHandler.handle("this is not json"))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void anEnvelopeWithoutAnEventIdIsTreatedAsAPoisonMessage() {
        assertThatThrownBy(() -> eventHandler.handle("{\"eventType\":\"VehicleTelemetryRecorded\"}"))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void anEventMissingRequiredPayloadFieldsIsTreatedAsAPoisonMessage() {
        String envelope = envelope(UUID.randomUUID(), "VehicleTelemetryRecorded",
                "{\"vehicleId\": \"BUS-042\"}");

        assertThatThrownBy(() -> eventHandler.handle(envelope))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void anUnhandledEventTypeIsIgnoredWithoutClaimingIt() {
        String envelope = envelope(UUID.randomUUID(), "SomeOtherThingHappened", "{}");

        assertThat(eventHandler.handle(envelope)).isEqualTo(EventOutcome.IGNORED);
        assertThat(processedEventCount()).isZero();
    }

    private void ingest(String sourceEventId, Instant recordedAt, double latitude, double longitude) {
        ingestionService.ingest(INGEST_KEY, new TelemetryIngestRequest(
                sourceEventId, VEHICLE, null, recordedAt, latitude, longitude, 24.0, 90.0, 30, 78));
    }

    private String envelope(UUID eventId, String eventType, String payloadJson) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "eventVersion": 1,
                  "occurredAt": "2026-09-16T10:15:01Z",
                  "aggregateId": "BUS-042",
                  "payload": %s
                }
                """.formatted(eventId, eventType, payloadJson);
    }

    private LatestVehicleTelemetry currentStateFor(String fleetNumber) {
        return queryService.findLatestVehicleTelemetry().stream()
                .filter(state -> state.vehicleId().equals(fleetNumber))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No current state for " + fleetNumber));
    }

    private int processedEventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class);
    }

    private int telemetryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry", Integer.class);
    }
}
