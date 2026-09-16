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
                    OffsetDateTime.ofInstant(request.recordedAt(), ZoneOffset.UTC),
                    OffsetDateTime.now(ZoneOffset.UTC),
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

        outboxEventWriter.write(
                "VehicleTelemetry",
                request.vehicleId(),
                "VehicleTelemetryRecorded",
                telemetryPayload(request)
        );

        return new TelemetryIngestResult(request.sourceEventId(), TelemetryIngestStatus.ACCEPTED);
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

