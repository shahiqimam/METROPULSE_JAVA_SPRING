package com.metropulse.outbox;

import com.metropulse.common.config.MetroPulseProperties;
import com.metropulse.outbox.publisher.OutboxPublisher;
import com.metropulse.support.PostgisIntegrationTest;
import com.metropulse.telemetry.api.TelemetryIngestRequest;
import com.metropulse.telemetry.application.TelemetryIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the outbox is for: the broker being down must not lose telemetry, and must not stop the
 * platform accepting it.
 *
 * <p>Failure is injected at the producer rather than by stopping a broker. That proves the
 * publisher's own behaviour — that a send failure leaves the row unpublished, records why, and that a
 * later run drains the backlog — and it does not prove anything about how a real Kafka client behaves
 * during an outage: reconnection, request timeouts and metadata refresh are the client's business.
 * The broker path itself is exercised in {@code VehicleStateConsumerKafkaIntegrationTest}.
 */
class OutboxPublisherIntegrationTest extends PostgisIntegrationTest {

    private static final String INGEST_KEY = "test-ingest-key";
    private static final String VEHICLE = "BUS-042";

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private MetroPulseProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void telemetryIsStillAcceptedWhileTheBrokerIsUnreachable() {
        ingest("evt-during-outage");

        // The write committed. Whether anyone can publish it is a separate concern, which is the
        // entire point of writing the event to the same database in the same transaction.
        assertThat(telemetryCount()).isEqualTo(1);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void aFailedPublishLeavesTheEventPendingAndSaysWhy() {
        ingest("evt-unpublishable");

        publisherThatCannotReach().publishPendingEvents();

        assertThat(pendingCount()).as("nothing is dropped on a failed send").isEqualTo(1);
        assertThat(attemptCount("evt-unpublishable")).isEqualTo(1);
        assertThat(lastError("evt-unpublishable")).contains("broker is down");
    }

    @Test
    void repeatedFailuresKeepCountingRatherThanGivingUp() {
        ingest("evt-retried");
        OutboxPublisher publisher = publisherThatCannotReach();

        publisher.publishPendingEvents();
        publisher.publishPendingEvents();
        publisher.publishPendingEvents();

        assertThat(pendingCount()).isEqualTo(1);
        assertThat(attemptCount("evt-retried")).isEqualTo(3);
    }

    @Test
    void theBacklogDrainsOnceTheBrokerComesBack() {
        ingest("evt-1");
        ingest("evt-2");
        ingest("evt-3");

        publisherThatCannotReach().publishPendingEvents();
        assertThat(pendingCount()).isEqualTo(3);

        RecordingKafkaTemplate recovered = new RecordingKafkaTemplate();
        publisherWith(recovered).publishPendingEvents();

        assertThat(pendingCount()).isZero();
        assertThat(recovered.sentKeys()).containsExactly(VEHICLE, VEHICLE, VEHICLE);
        assertThat(recovered.sentPayloads()).hasSize(3);
    }

    @Test
    void theBacklogIsPublishedInTheOrderItWasWritten() {
        ingest("evt-first");
        ingest("evt-second");
        ingest("evt-third");
        publisherThatCannotReach().publishPendingEvents();

        RecordingKafkaTemplate recovered = new RecordingKafkaTemplate();
        publisherWith(recovered).publishPendingEvents();

        // Ordering matters downstream: the consumer's no-rewind rule treats a late event as history,
        // so a backlog published out of order would discard state it should have applied.
        assertThat(recovered.sentPayloads())
                .extracting(payload -> payload.replaceAll("(?s).*\"sourceEventId\"\\s*:\\s*\"([^\"]+)\".*", "$1"))
                .containsExactly("evt-first", "evt-second", "evt-third");
    }

    @Test
    void recoveryClearsTheErrorItRecordedWhileFailing() {
        ingest("evt-recovers");
        publisherThatCannotReach().publishPendingEvents();
        assertThat(lastError("evt-recovers")).isNotNull();

        publisherWith(new RecordingKafkaTemplate()).publishPendingEvents();

        assertThat(lastError("evt-recovers")).isNull();
        assertThat(attemptCount("evt-recovers")).isEqualTo(2);
    }

    @Test
    void anAlreadyPublishedEventIsNotSentAgain() {
        ingest("evt-once");
        RecordingKafkaTemplate kafka = new RecordingKafkaTemplate();
        OutboxPublisher publisher = publisherWith(kafka);

        publisher.publishPendingEvents();
        publisher.publishPendingEvents();
        publisher.publishPendingEvents();

        assertThat(kafka.sentPayloads()).hasSize(1);
        assertThat(pendingCount()).isZero();
    }

    private void ingest(String sourceEventId) {
        ingestionService.ingest(INGEST_KEY, new TelemetryIngestRequest(
                sourceEventId, VEHICLE, null, Instant.now(), 40.7152, -73.9980, 24.0, 90.0, 30, 78));
    }

    private OutboxPublisher publisherThatCannotReach() {
        return publisherWith(new UnreachableKafkaTemplate());
    }

    /**
     * A publisher this test drives by hand.
     *
     * <p>The application's own publisher is disabled across the integration suite so that a scheduled
     * job cannot move state underneath a test. This one is switched on deliberately, keeping the real
     * topic and batch size, so what runs here is the production code path rather than a copy of it.
     */
    private OutboxPublisher publisherWith(KafkaTemplate<String, String> kafkaTemplate) {
        MetroPulseProperties.Outbox outbox = properties.outbox();
        MetroPulseProperties enabled = new MetroPulseProperties(
                properties.telemetry(),
                new MetroPulseProperties.Outbox(
                        true, outbox.topic(), outbox.batchSize(), outbox.publishDelayMs()),
                properties.auth());

        return new OutboxPublisher(jdbcTemplate, kafkaTemplate, enabled);
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }

    private int telemetryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry", Integer.class);
    }

    private int attemptCount(String sourceEventId) {
        return jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM outbox_event
                WHERE payload::text LIKE ?
                """, Integer.class, "%" + sourceEventId + "%");
    }

    private String lastError(String sourceEventId) {
        return jdbcTemplate.queryForObject("""
                SELECT last_error FROM outbox_event
                WHERE payload::text LIKE ?
                """, String.class, "%" + sourceEventId + "%");
    }

    /** A producer that cannot reach the broker, the way a client behaves during an outage. */
    private static final class UnreachableKafkaTemplate extends KafkaTemplate<String, String> {

        private UnreachableKafkaTemplate() {
            super(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            return CompletableFuture.failedFuture(new IllegalStateException("the broker is down"));
        }
    }

    /** A producer that works, and remembers what it was given. */
    private static final class RecordingKafkaTemplate extends KafkaTemplate<String, String> {

        private final List<String> keys = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();

        private RecordingKafkaTemplate() {
            super(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            keys.add(key);
            payloads.add(data);
            return CompletableFuture.completedFuture(null);
        }

        List<String> sentKeys() {
            return List.copyOf(keys);
        }

        List<String> sentPayloads() {
            return List.copyOf(payloads);
        }
    }
}
