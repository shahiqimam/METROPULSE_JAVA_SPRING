package com.metropulse.operations.consumer;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Records which events a consumer has already applied.
 *
 * <p>Kafka delivers at-least-once: after a rebalance, a retry or a restart before the offset was
 * committed, a consumer can legitimately see the same event again. Claiming an event and applying it
 * in one transaction makes redelivery a no-op instead of a second application.
 */
@Component
public class ProcessedEventLedger {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Claims an event for a consumer.
     *
     * <p>Must be called inside the same transaction as the work the event triggers, so the claim and
     * the work commit or roll back together.
     *
     * @return true when this consumer had not processed the event before, false when it is a replay
     */
    public boolean claim(UUID eventId, String consumerName) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO processed_event (event_id, consumer_name) VALUES (?, ?)",
                    eventId,
                    consumerName);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
