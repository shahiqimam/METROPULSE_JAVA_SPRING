package com.metropulse.telemetry.read;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class TelemetryQueryService {

    private final JdbcTemplate jdbcTemplate;

    public TelemetryQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<LatestVehicleTelemetry> findLatestVehicleTelemetry() {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (v.id)
                    v.fleet_number,
                    v.vehicle_type,
                    v.propulsion_type,
                    v.capacity,
                    v.status,
                    vt.source_event_id,
                    vt.recorded_at,
                    vt.received_at,
                    ST_Y(vt.location) AS latitude,
                    ST_X(vt.location) AS longitude,
                    vt.speed_kph,
                    vt.heading_degrees,
                    vt.occupancy_estimate,
                    vt.battery_percent
                FROM vehicle v
                JOIN vehicle_telemetry vt ON vt.vehicle_id = v.id
                ORDER BY v.id, vt.recorded_at DESC, vt.received_at DESC
                """,
                (rs, rowNum) -> new LatestVehicleTelemetry(
                        rs.getString("fleet_number"),
                        rs.getString("vehicle_type"),
                        rs.getString("propulsion_type"),
                        rs.getInt("capacity"),
                        rs.getString("status"),
                        rs.getString("source_event_id"),
                        toInstant(rs.getTimestamp("recorded_at")),
                        toInstant(rs.getTimestamp("received_at")),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"),
                        rs.getBigDecimal("speed_kph"),
                        rs.getBigDecimal("heading_degrees"),
                        rs.getInt("occupancy_estimate"),
                        (Integer) rs.getObject("battery_percent")
                ));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
