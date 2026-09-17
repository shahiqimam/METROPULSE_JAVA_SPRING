package com.metropulse.operations.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Writes the current operational state of a vehicle.
 *
 * <p>This is the only place that touches {@code vehicle_current_state}. It is called by the
 * operational-state Kafka consumer, so the projection is derived from published events rather than
 * from the ingest request: ingest owns history and the outbox, the consumer owns current state.
 *
 * <p>The row is keyed by vehicle, so the write is an upsert. The {@code WHERE} guard on the conflict
 * branch is the no-rewind rule: an event recorded before the state we already hold is still valid
 * history, but it must not move current state backwards. That matters more here than it did inside
 * ingest, because Kafka only orders events within a partition, and a retried event can arrive after
 * newer ones.
 *
 * <p>Route progress and route deviation are computed by PostGIS against the geometry of the route the
 * vehicle is assigned to. Progress is the normalised position along the route line (0.0 = start,
 * 1.0 = end); deviation is the distance from the vehicle to that line, measured on the geography type
 * so the result is in meters rather than degrees.
 */
@Component
public class VehicleStateProjection {

    private final JdbcTemplate jdbcTemplate;

    public VehicleStateProjection(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Applies one observation to current state.
     *
     * @return true when the observation became the vehicle's current state, false when it was older
     *         than the state already held and was therefore ignored
     */
    public boolean apply(VehicleObservation observation) {
        Long vehicleRowId = findVehicleRowId(observation.vehicleId());
        if (vehicleRowId == null) {
            throw new UnknownVehicleInEventException(observation.vehicleId());
        }

        int updated = jdbcTemplate.update("""
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
                observation.sourceEventId(),
                OffsetDateTime.ofInstant(observation.recordedAt(), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(observation.receivedAt(), java.time.ZoneOffset.UTC),
                observation.speedKph(),
                observation.headingDegrees(),
                observation.occupancyEstimate(),
                observation.batteryPercent(),
                observation.longitude(),
                observation.latitude(),
                vehicleRowId);

        return updated > 0;
    }

    private Long findVehicleRowId(String fleetNumber) {
        return jdbcTemplate.query(
                "SELECT id FROM vehicle WHERE fleet_number = ?",
                rs -> rs.next() ? rs.getLong("id") : null,
                fleetNumber);
    }
}
