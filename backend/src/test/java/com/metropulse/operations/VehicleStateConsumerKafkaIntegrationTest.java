package com.metropulse.operations;

import com.metropulse.support.PostgisIntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the operational-state consumer through a real Kafka broker.
 *
 * <p>The broker runs in-process (Spring Kafka's embedded broker), so this exercises what the
 * handler-level tests cannot: the listener subscribing to a topic, records arriving over the wire,
 * and the error handler routing an event it can never apply to the dead-letter topic instead of
 * blocking the partition.
 */
@SpringBootTest(properties = {
        "metropulse.outbox.enabled=false",
        "metropulse.operations.consumer-enabled=true",
        "metropulse.scheduling-enabled=false"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {
                VehicleStateConsumerKafkaIntegrationTest.TOPIC,
                VehicleStateConsumerKafkaIntegrationTest.DLT
        }
)
class VehicleStateConsumerKafkaIntegrationTest extends PostgisIntegrationTest {

    static final String TOPIC = "metropulse.test.telemetry.v1";
    static final String DLT = TOPIC + ".DLT";

    private static final String VEHICLE = "BUS-042";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty(EmbeddedKafkaBroker.SPRING_EMBEDDED_KAFKA_BROKERS));
        registry.add("metropulse.outbox.topic", () -> TOPIC);
        registry.add("metropulse.operations.consumer-group", () -> "metropulse-test-operational-state");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Consumer<String, String> deadLetterConsumer;

    @BeforeEach
    void clearStateAndSubscribeToTheDeadLetterTopic() {
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");

        Map<String, Object> consumerProperties =
                KafkaTestUtils.consumerProps("dlt-observer-" + UUID.randomUUID(), "false", embeddedKafka);
        deadLetterConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                consumerProperties,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        embeddedKafka.consumeFromAnEmbeddedTopic(deadLetterConsumer, DLT);
    }

    @AfterEach
    void closeDeadLetterConsumer() {
        if (deadLetterConsumer != null) {
            deadLetterConsumer.close();
        }
    }

    @Test
    void anEventPublishedToKafkaBecomesCurrentState() {
        kafkaTemplate.send(TOPIC, VEHICLE, envelope(UUID.randomUUID(), "evt-kafka-1", "2026-09-16T10:15:00Z", VEHICLE));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(currentSourceEventId()).isEqualTo("evt-kafka-1"));
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        String envelope = envelope(eventId, "evt-kafka-dup", "2026-09-16T10:15:00Z", VEHICLE);

        kafkaTemplate.send(TOPIC, VEHICLE, envelope);
        kafkaTemplate.send(TOPIC, VEHICLE, envelope);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(currentSourceEventId()).isEqualTo("evt-kafka-dup"));

        // Give the second delivery time to be consumed before asserting it changed nothing.
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(processedEventCount()).isEqualTo(1));
    }

    @Test
    void anEventForAnUnknownVehicleGoesToTheDeadLetterTopic() {
        kafkaTemplate.send(TOPIC, "BUS-ghost",
                envelope(UUID.randomUUID(), "evt-kafka-ghost", "2026-09-16T10:15:00Z", "BUS-does-not-exist"));

        assertThat(awaitDeadLetterContaining("evt-kafka-ghost")).isNotNull();
        assertThat(currentStateRowCount()).isZero();
    }

    @Test
    void aMalformedEventGoesToTheDeadLetterTopicWithoutBlockingLaterEvents() {
        kafkaTemplate.send(TOPIC, VEHICLE, "this is not an event envelope");
        kafkaTemplate.send(TOPIC, VEHICLE, envelope(UUID.randomUUID(), "evt-after-poison", "2026-09-16T10:16:00Z", VEHICLE));

        assertThat(awaitDeadLetterContaining("this is not an event envelope")).isNotNull();

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(currentSourceEventId()).isEqualTo("evt-after-poison"));
    }

    /**
     * Waits for a dead-lettered record whose body contains {@code expected}.
     *
     * <p>The dead-letter topic is shared by the whole class and keeps what earlier tests produced, so
     * this skips records belonging to other tests rather than assuming the first one is ours.
     */
    private ConsumerRecord<String, String> awaitDeadLetterContaining(String expected) {
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records =
                    KafkaTestUtils.getRecords(deadLetterConsumer, Duration.ofSeconds(2), 1);
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(expected)) {
                    return record;
                }
            }
        }

        throw new AssertionError("No dead-lettered event containing: " + expected);
    }

    private String envelope(UUID eventId, String sourceEventId, String recordedAt, String vehicleId) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "VehicleTelemetryRecorded",
                  "eventVersion": 1,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "payload": {
                    "sourceEventId": "%s",
                    "vehicleId": "%s",
                    "recordedAt": "%s",
                    "receivedAt": "%s",
                    "latitude": 40.7152,
                    "longitude": -73.9980,
                    "speedKph": 24.0,
                    "headingDegrees": 90.0,
                    "occupancyEstimate": 30,
                    "batteryPercent": 78
                  }
                }
                """.formatted(eventId, recordedAt, vehicleId, sourceEventId, vehicleId, recordedAt, recordedAt);
    }

    private String currentSourceEventId() {
        return jdbcTemplate.query(
                "SELECT source_event_id FROM vehicle_current_state",
                rs -> rs.next() ? rs.getString("source_event_id") : null);
    }

    private int currentStateRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vehicle_current_state", Integer.class);
    }

    private int processedEventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class);
    }
}
