package com.metropulse.schedule.read;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScheduleQueryService {

    private final JdbcTemplate jdbcTemplate;

    public ScheduleQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RouteSummary> findRoutes() {
        return jdbcTemplate.query("""
                SELECT
                    r.code,
                    r.short_name,
                    r.long_name,
                    a.name AS agency_name,
                    r.active,
                    COUNT(DISTINCT st.stop_id)::int AS stop_count,
                    COUNT(DISTINCT t.id)::int AS trip_count,
                    ST_NPoints(r.geometry)::int AS route_point_count
                FROM route r
                JOIN agency a ON a.id = r.agency_id
                LEFT JOIN trip t ON t.route_id = r.id
                LEFT JOIN stop_time st ON st.trip_id = t.id
                GROUP BY r.id, a.name
                ORDER BY r.code
                """,
                (rs, rowNum) -> new RouteSummary(
                        rs.getString("code"),
                        rs.getString("short_name"),
                        rs.getString("long_name"),
                        rs.getString("agency_name"),
                        rs.getBoolean("active"),
                        rs.getInt("stop_count"),
                        rs.getInt("trip_count"),
                        rs.getInt("route_point_count")
                ));
    }

    public List<RouteStop> findRouteStops(String routeCode) {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (s.id)
                    s.code AS stop_code,
                    s.name AS stop_name,
                    st.stop_sequence,
                    st.planned_arrival_seconds,
                    st.planned_departure_seconds,
                    ST_Y(s.location) AS latitude,
                    ST_X(s.location) AS longitude
                FROM route r
                JOIN trip t ON t.route_id = r.id
                JOIN stop_time st ON st.trip_id = t.id
                JOIN stop s ON s.id = st.stop_id
                WHERE r.code = ?
                ORDER BY s.id, st.stop_sequence, st.planned_arrival_seconds
                """,
                (rs, rowNum) -> new RouteStop(
                        rs.getString("stop_code"),
                        rs.getString("stop_name"),
                        rs.getInt("stop_sequence"),
                        rs.getInt("planned_arrival_seconds"),
                        rs.getInt("planned_departure_seconds"),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude")
                ),
                routeCode
        ).stream()
                .sorted((left, right) -> Integer.compare(left.stopSequence(), right.stopSequence()))
                .toList();
    }
}
