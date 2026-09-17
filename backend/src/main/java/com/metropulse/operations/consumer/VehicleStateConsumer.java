package com.metropulse.operations.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Subscribes to published telemetry events and hands each one to {@link TelemetryEventHandler}.
 *
 * <p>This is the consumer side of the outbox: ingest commits the observation and an outbox row, the
 * outbox publisher sends the event to Kafka, and this listener derives operational state from it.
 * Deriving state here rather than in the ingest request means it is rebuilt from the same events any
 * other consumer sees, and a slow projection cannot slow down ingest.
 *
 * <p>Telemetry is partitioned by vehicle, so events for one vehicle stay ordered relative to each
 * other. Ordering across vehicles is not guaranteed and is not needed: state is per vehicle.
 *
 * <p>Failure handling lives in {@link KafkaConsumerConfig}: an event that cannot be applied is
 * retried a bounded number of times and then routed to the dead-letter topic, so one bad event does
 * not block the partition.
 */
@Component
@ConditionalOnProperty(name = "metropulse.operations.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class VehicleStateConsumer {

    private static final Logger log = LoggerFactory.getLogger(VehicleStateConsumer.class);

    private final TelemetryEventHandler telemetryEventHandler;

    public VehicleStateConsumer(TelemetryEventHandler telemetryEventHandler) {
        this.telemetryEventHandler = telemetryEventHandler;
    }

    @KafkaListener(
            topics = "${metropulse.outbox.topic}",
            groupId = "${metropulse.operations.consumer-group:metropulse-operational-state}"
    )
    public void onTelemetryEvent(String message, @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        TelemetryEventHandler.EventOutcome outcome = telemetryEventHandler.handle(message);
        log.debug("Telemetry event for key {} handled with outcome {}.", key, outcome);
    }
}
