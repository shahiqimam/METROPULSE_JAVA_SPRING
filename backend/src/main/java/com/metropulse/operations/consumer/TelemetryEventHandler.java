package com.metropulse.operations.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metropulse.operations.projection.VehicleObservation;
import com.metropulse.operations.projection.VehicleStateProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies one published telemetry event to current vehicle state.
 *
 * <p>This is a separate bean from {@link VehicleStateConsumer} on purpose. Spring's
 * {@code @Transactional} works through a proxy, so a listener calling a transactional method on
 * itself would run outside a transaction, and the idempotency claim would then commit independently
 * of the projection write. Going through another bean means the claim and the projection really do
 * share one transaction.
 *
 * <p>Delivery is at-least-once, so the handler claims the event id in {@link ProcessedEventLedger}
 * before doing any work. A replay finds the claim taken and returns without touching state.
 */
@Component
public class TelemetryEventHandler {

    public static final String CONSUMER_NAME = "operational-state";
    public static final String HANDLED_EVENT_TYPE = "VehicleTelemetryRecorded";

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventHandler.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEventLedger processedEventLedger;
    private final VehicleStateProjection vehicleStateProjection;

    public TelemetryEventHandler(
            ObjectMapper objectMapper,
            ProcessedEventLedger processedEventLedger,
            VehicleStateProjection vehicleStateProjection
    ) {
        this.objectMapper = objectMapper;
        this.processedEventLedger = processedEventLedger;
        this.vehicleStateProjection = vehicleStateProjection;
    }

    @Transactional
    public EventOutcome handle(String message) {
        JsonNode envelope = readEnvelope(message);
        UUID eventId = eventId(envelope);
        String eventType = envelope.path("eventType").asText();

        if (!HANDLED_EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring event {} of unhandled type {}.", eventId, eventType);
            return EventOutcome.IGNORED;
        }

        if (!processedEventLedger.claim(eventId, CONSUMER_NAME)) {
            log.debug("Event {} was already processed by {}; skipping.", eventId, CONSUMER_NAME);
            return EventOutcome.DUPLICATE;
        }

        VehicleObservation observation = readObservation(envelope.path("payload"));

        if (!vehicleStateProjection.apply(observation)) {
            log.debug("Event {} for {} is older than current state; kept as history only.",
                    eventId, observation.vehicleId());
            return EventOutcome.SUPERSEDED;
        }

        return EventOutcome.APPLIED;
    }

    private JsonNode readEnvelope(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception ex) {
            throw new MalformedEventException("Event is not readable JSON.", ex);
        }
    }

    private UUID eventId(JsonNode envelope) {
        String value = envelope.path("eventId").asText(null);
        if (value == null || value.isBlank()) {
            throw new MalformedEventException("Event envelope has no eventId.", null);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new MalformedEventException("Event envelope has a non-UUID eventId: " + value, ex);
        }
    }

    private VehicleObservation readObservation(JsonNode payload) {
        if (payload.isMissingNode() || payload.isNull()) {
            throw new MalformedEventException("Event envelope has no payload.", null);
        }

        try {
            return new VehicleObservation(
                    requiredText(payload, "sourceEventId"),
                    requiredText(payload, "vehicleId"),
                    payload.hasNonNull("tripId") ? payload.get("tripId").asText() : null,
                    Instant.parse(requiredText(payload, "recordedAt")),
                    payload.hasNonNull("receivedAt")
                            ? Instant.parse(payload.get("receivedAt").asText())
                            : Instant.now(),
                    payload.path("latitude").asDouble(),
                    payload.path("longitude").asDouble(),
                    payload.path("speedKph").asDouble(),
                    payload.path("headingDegrees").asDouble(),
                    payload.path("occupancyEstimate").asInt(),
                    payload.hasNonNull("batteryPercent") ? payload.get("batteryPercent").asInt() : null);
        } catch (MalformedEventException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MalformedEventException("Event payload could not be read.", ex);
        }
    }

    private String requiredText(JsonNode payload, String field) {
        if (!payload.hasNonNull(field)) {
            throw new MalformedEventException("Event payload is missing " + field + ".", null);
        }
        return payload.get(field).asText();
    }

    /** What the handler did with an event. */
    public enum EventOutcome {
        /** The observation became the vehicle's current state. */
        APPLIED,
        /** The event had already been processed by this consumer. */
        DUPLICATE,
        /** The observation was older than the state already held, so state was left alone. */
        SUPERSEDED,
        /** The event was not of a type this consumer handles. */
        IGNORED
    }
}
