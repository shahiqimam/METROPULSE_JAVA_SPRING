package com.metropulse.telemetry.read;

import com.metropulse.operations.domain.ConnectivityState;
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

    /**
     * Reads the current operational state of every vehicle that has reported.
     *
     * <p>The state table holds one row per vehicle, so this is a plain join rather than a
     * "latest row per vehicle" scan of the telemetry history. Telemetry age is measured by the
     * database clock and classified in {@link ConnectivityState} so the thresholds stay unit-testable.
     */
    public List<LatestVehicleTelemetry> findLatestVehicleTelemetry() {
        return jdbcTemplate.query("""
                SELECT
                    v.fleet_number,
                    v.vehicle_type,
                    v.propulsion_type,
                    v.capacity,
                    v.status,
                    vcs.source_event_id,
                    vcs.recorded_at,
                    vcs.received_at,
                    ST_Y(vcs.location) AS latitude,
                    ST_X(vcs.location) AS longitude,
                    vcs.speed_kph,
                    vcs.heading_degrees,
                    vcs.occupancy_estimate,
                    vcs.battery_percent,
                    r.code AS route_code,
                    vcs.route_progress,
                    vcs.route_deviation_meters,
                    t.trip_code,
                    vcs.schedule_deviation_seconds,
                    ns.name AS next_stop_name,
                    EXTRACT(EPOCH FROM (now() - vcs.recorded_at)) AS telemetry_age_seconds
                FROM vehicle_current_state vcs
                JOIN vehicle v ON v.id = vcs.vehicle_id
                LEFT JOIN route r ON r.id = vcs.route_id
                LEFT JOIN trip t ON t.id = vcs.active_trip_id
                LEFT JOIN stop ns ON ns.id = vcs.next_stop_id
                ORDER BY v.fleet_number
                """,
                (rs, rowNum) -> {
                    double telemetryAgeSeconds = rs.getDouble("telemetry_age_seconds");
                    return new LatestVehicleTelemetry(
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
                            (Integer) rs.getObject("battery_percent"),
                            rs.getString("route_code"),
                            rs.getBigDecimal("route_progress"),
                            rs.getBigDecimal("route_deviation_meters"),
                            rs.getString("trip_code"),
                            (Integer) rs.getObject("schedule_deviation_seconds"),
                            rs.getString("next_stop_name"),
                            telemetryAgeSeconds,
                            ConnectivityState.classify(telemetryAgeSeconds));
                });
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
