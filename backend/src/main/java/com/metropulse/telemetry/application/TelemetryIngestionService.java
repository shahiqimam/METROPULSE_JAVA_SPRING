package com.metropulse.telemetry.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metropulse.common.config.MetroPulseProperties;
import com.metropulse.outbox.application.OutboxEventWriter;
import com.metropulse.telemetry.api.TelemetryIngestRequest;
import com.metropulse.telemetry.domain.InvalidIngestKeyException;
import com.metropulse.telemetry.domain.TelemetryIngestResult;
import com.metropulse.telemetry.domain.TelemetryIngestStatus;
import com.metropulse.telemetry.domain.UnknownVehicleException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TelemetryIngestionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxEventWriter outboxEventWriter;
    private final MetroPulseProperties properties;

    public TelemetryIngestionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            OutboxEventWriter outboxEventWriter,
            MetroPulseProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
        this.properties = properties;
    }

    @Transactional
    public TelemetryIngestResult ingest(String ingestKey, TelemetryIngestRequest request) {
        validateIngestKey(ingestKey);

        if (sourceEventExists(request.sourceEventId())) {
            return new TelemetryIngestResult(request.sourceEventId(), TelemetryIngestStatus.DUPLICATE);
        }

        Long vehicleRowId = findVehicleRowId(request.vehicleId());
        if (vehicleRowId == null) {
            throw new UnknownVehicleException(request.vehicleId());
        }

        OffsetDateTime recordedAt = OffsetDateTime.ofInstant(request.recordedAt(), ZoneOffset.UTC);
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        try {
            jdbcTemplate.update("""
                    INSERT INTO vehicle_telemetry (
                        source_event_id,
                        vehicle_id,
                        recorded_at,
                        received_at,
                        location,
                        speed_kph,
                        heading_degrees,
                        occupancy_estimate,
                        battery_percent,
                        raw_payload
                    )
                    VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, ?, ?, CAST(? AS jsonb))
                    """,
                    request.sourceEventId(),
                    vehicleRowId,
                    recordedAt,
                    receivedAt,
                    request.longitude(),
                    request.latitude(),
                    request.speedKph(),
                    request.headingDegrees(),
                    request.occupancyEstimate(),
                    request.batteryPercent(),
                    toJson(request));
        } catch (DuplicateKeyException ex) {
            return new TelemetryIngestResult(request.sourceEventId(), TelemetryIngestStatus.DUPLICATE);
        }

        updateCurrentState(vehicleRowId, request, recordedAt, receivedAt);

        outboxEventWriter.write(
                "VehicleTelemetry",
                request.vehicleId(),
                "VehicleTelemetryRecorded",
                telemetryPayload(request)
        );

        return new TelemetryIngestResult(request.sourceEventId(), TelemetryIngestStatus.ACCEPTED);
    }

    /**
     * Projects the observation onto the vehicle's current state.
     *
     * <p>The row is keyed by vehicle, so the projection is an upsert. The {@code WHERE} guard on the
     * conflict branch implements the no-rewind rule: a telemetry event that was recorded before the
     * state we already hold is still stored historically, but it must not replace current state.
     *
     * <p>Route progress and route deviation are computed by PostGIS against the geometry of the route
     * the vehicle is assigned to. Progress is the normalised position along the route line
     * (0.0 = start, 1.0 = end); deviation is the metric distance from the vehicle to that line,
     * measured on the geography type so the result is in meters rather than degrees.
     */
    private void updateCurrentState(
            Long vehicleRowId,
            TelemetryIngestRequest request,
            OffsetDateTime recordedAt,
            OffsetDateTime receivedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO vehicle_current_state (
                    vehicle_id,
                    source_event_id,
                    recorded_at,
                    received_at,
                    location,
                    speed_kph,
                    heading_degrees,
                    occupancy_estimate,
                    battery_percent,
                    route_id,
                    route_progress,
                    route_deviation_meters,
                    updated_at
                )
                SELECT
                    vehicle.id,
                    ?,
                    ?,
                    ?,
                    observation.location,
                    ?,
                    ?,
                    ?,
                    ?,
                    route.id,
                    ST_LineLocatePoint(route.geometry, observation.location),
                    ST_Distance(route.geometry::geography, observation.location::geography),
                    now()
                FROM vehicle
                CROSS JOIN LATERAL (
                    SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS location
                ) AS observation
                LEFT JOIN route ON route.id = vehicle.assigned_route_id
                WHERE vehicle.id = ?
                ON CONFLICT (vehicle_id) DO UPDATE
                SET source_event_id = EXCLUDED.source_event_id,
                    recorded_at = EXCLUDED.recorded_at,
                    received_at = EXCLUDED.received_at,
                    location = EXCLUDED.location,
                    speed_kph = EXCLUDED.speed_kph,
                    heading_degrees = EXCLUDED.heading_degrees,
                    occupancy_estimate = EXCLUDED.occupancy_estimate,
                    battery_percent = EXCLUDED.battery_percent,
                    route_id = EXCLUDED.route_id,
                    route_progress = EXCLUDED.route_progress,
                    route_deviation_meters = EXCLUDED.route_deviation_meters,
                    updated_at = now()
                WHERE vehicle_current_state.recorded_at < EXCLUDED.recorded_at
                   OR (
                       vehicle_current_state.recorded_at = EXCLUDED.recorded_at
                       AND vehicle_current_state.received_at <= EXCLUDED.received_at
                   )
                """,
                request.sourceEventId(),
                recordedAt,
                receivedAt,
                request.speedKph(),
                request.headingDegrees(),
                request.occupancyEstimate(),
                request.batteryPercent(),
                request.longitude(),
                request.latitude(),
                vehicleRowId);
    }

    private void validateIngestKey(String ingestKey) {
        String expected = properties.telemetry().ingestKey();
        if (expected == null || expected.isBlank() || !expected.equals(ingestKey)) {
            throw new InvalidIngestKeyException();
        }
    }

    private boolean sourceEventExists(String sourceEventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle_telemetry WHERE source_event_id = ?",
                Integer.class,
                sourceEventId);
        return count != null && count > 0;
    }

    private Long findVehicleRowId(String fleetNumber) {
        return jdbcTemplate.query(
                "SELECT id FROM vehicle WHERE fleet_number = ?",
                rs -> rs.next() ? rs.getLong("id") : null,
                fleetNumber);
    }

    private Map<String, Object> telemetryPayload(TelemetryIngestRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceEventId", request.sourceEventId());
        payload.put("vehicleId", request.vehicleId());
        payload.put("recordedAt", request.recordedAt().toString());
        payload.put("latitude", request.latitude());
        payload.put("longitude", request.longitude());
        payload.put("speedKph", request.speedKph());
        payload.put("headingDegrees", request.headingDegrees());
        payload.put("occupancyEstimate", request.occupancyEstimate());
        payload.put("batteryPercent", request.batteryPercent());
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize telemetry payload.", ex);
        }
    }
}
