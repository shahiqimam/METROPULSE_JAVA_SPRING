package com.metropulse.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxEventWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID write(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", eventType,
                "eventVersion", 1,
                "occurredAt", Instant.now().toString(),
                "aggregateId", aggregateId,
                "payload", payload
        );

        jdbcTemplate.update("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                """,
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                toJson(envelope));

        return eventId;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload.", ex);
        }
    }
}
