package com.metropulse.support;

import com.metropulse.operations.consumer.TelemetryEventHandler;
import com.metropulse.operations.consumer.TelemetryEventHandler.EventOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Test double for the outbox publisher plus Kafka: reads the events ingest wrote and hands them to
 * the operational-state handler, in the order they were created.
 *
 * <p>It stands in for the broker so the ingest-to-projection path can be tested end to end without
 * one. What it does not prove is broker behaviour: partitioning, offsets, consumer-group rebalances,
 * redelivery or the dead-letter topic. Those need a real Kafka and are exercised separately.
 */
@Component
public class OutboxPipeline {

    private final JdbcTemplate jdbcTemplate;
    private final TelemetryEventHandler telemetryEventHandler;

    public OutboxPipeline(JdbcTemplate jdbcTemplate, TelemetryEventHandler telemetryEventHandler) {
        this.jdbcTemplate = jdbcTemplate;
        this.telemetryEventHandler = telemetryEventHandler;
    }

    /** Delivers every unpublished outbox event once, marking it published, and returns the outcomes. */
    public List<EventOutcome> drain() {
        List<String> payloads = pendingPayloads();
        List<EventOutcome> outcomes = payloads.stream().map(telemetryEventHandler::handle).toList();
        jdbcTemplate.update("UPDATE outbox_event SET published_at = now() WHERE published_at IS NULL");
        return outcomes;
    }

    /** The envelope of the most recently created outbox event, as JSON. */
    public String lastPayload() {
        return jdbcTemplate.queryForObject("""
                SELECT payload::text
                FROM outbox_event
                ORDER BY created_at DESC, id
                LIMIT 1
                """, String.class);
    }

    /** Redelivers an envelope, the way Kafka would after a rebalance or a restart. */
    public EventOutcome redeliver(String payload) {
        return telemetryEventHandler.handle(payload);
    }

    private List<String> pendingPayloads() {
        return jdbcTemplate.queryForList("""
                SELECT payload::text AS payload
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY created_at, id
                """, String.class);
    }
}
