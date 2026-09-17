package com.metropulse.outbox.publisher;

import com.metropulse.common.config.MetroPulseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MetroPulseProperties properties;

    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            MetroPulseProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${metropulse.outbox.publish-delay-ms:5000}")
    public void publishPendingEvents() {
        if (!properties.outbox().enabled()) {
            return;
        }

        List<OutboxEvent> events = findPendingEvents();
        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    private List<OutboxEvent> findPendingEvents() {
        return jdbcTemplate.query("""
                SELECT id, aggregate_id, event_type, payload::text AS payload
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, rowNum) -> new OutboxEvent(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload")
                ),
                properties.outbox().batchSize()
        );
    }

    private void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(properties.outbox().topic(), event.aggregateId(), event.payload())
                    .get(10, TimeUnit.SECONDS);
            markPublished(event.id());
        } catch (Exception ex) {
            markFailed(event.id(), ex);
            log.warn("Failed to publish outbox event {} of type {}.", event.id(), event.eventType(), ex);
        }
    }

    private void markPublished(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE outbox_event
                SET published_at = now(),
                    attempt_count = attempt_count + 1,
                    last_error = NULL
                WHERE id = ?
                  AND published_at IS NULL
                """,
                eventId
        );
    }

    private void markFailed(UUID eventId, Exception ex) {
        jdbcTemplate.update("""
                UPDATE outbox_event
                SET attempt_count = attempt_count + 1,
                    last_error = ?
                WHERE id = ?
                  AND published_at IS NULL
                """,
                rootMessage(ex),
                eventId
        );
    }

    private String rootMessage(Exception ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getName() : message;
    }
}
