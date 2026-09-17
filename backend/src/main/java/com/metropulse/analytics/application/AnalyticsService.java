package com.metropulse.analytics.application;

import com.metropulse.analytics.domain.EvAnalytics;
import com.metropulse.analytics.domain.IncidentAnalytics;
import com.metropulse.analytics.domain.ServiceRegularityAnalytics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Operational metrics over stored history.
 *
 * <p>Every figure here is computed from rows the platform actually wrote. Nothing is estimated from
 * a model, and nothing that the data cannot support is reported — see the note on punctuality below.
 *
 * <h2>Why there is no punctuality endpoint</h2>
 *
 * <p>Punctuality means measuring actual arrivals against scheduled ones. MetroPulse stores the
 * schedule and it stores telemetry, but it does not yet detect arrivals at stops, so there is no
 * actual arrival time to compare. Reporting a punctuality number derived from anything else — route
 * progress against elapsed time, say — would be inventing a measurement and labelling it with a word
 * that means something specific in transit.
 *
 * <p>What the data does support is <em>regularity</em>: how evenly spaced the service is running.
 * That is what {@link #serviceRegularity} reports, under its own name.
 */
@Service
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * How evenly spaced each route's service has been running.
     *
     * <p>Derived from headway conditions rather than from raw spacing history, because conditions are
     * what the rules actually asserted. A route with no conditions in the window ran within target
     * the whole time, which is the interesting thing to be able to say.
     */
    public List<ServiceRegularityAnalytics> serviceRegularity(Duration window) {
        OffsetDateTime since = since(window);

        return jdbcTemplate.query("""
                SELECT
                    r.code AS route_code,
                    r.target_headway_seconds,
                    COUNT(hc.fingerprint)::int AS conditions_now,
                    COUNT(hc.fingerprint) FILTER (WHERE hc.condition_type = 'BUNCHING')::int AS bunching_now,
                    COUNT(hc.fingerprint) FILTER (WHERE hc.condition_type = 'EXCESSIVE_GAP')::int AS gaps_now,
                    COUNT(a.id) FILTER (WHERE a.type = 'BUNCHING')::int AS bunching_alerts,
                    COUNT(a.id) FILTER (WHERE a.type = 'EXCESSIVE_GAP')::int AS gap_alerts,
                    COALESCE(AVG(EXTRACT(EPOCH FROM (
                        COALESCE(a.closed_at, now()) - a.opened_at
                    )) ) FILTER (WHERE a.id IS NOT NULL), 0)::numeric(10,1) AS average_alert_seconds
                FROM route r
                LEFT JOIN headway_condition hc ON hc.route_id = r.id
                LEFT JOIN alert a
                       ON a.route_id = r.id
                      AND a.type IN ('BUNCHING', 'EXCESSIVE_GAP')
                      AND a.opened_at >= ?
                WHERE r.active
                GROUP BY r.id, r.code, r.target_headway_seconds
                ORDER BY r.code
                """,
                (rs, rowNum) -> new ServiceRegularityAnalytics(
                        rs.getString("route_code"),
                        rs.getInt("target_headway_seconds"),
                        rs.getInt("conditions_now"),
                        rs.getInt("bunching_now"),
                        rs.getInt("gaps_now"),
                        rs.getInt("bunching_alerts"),
                        rs.getInt("gap_alerts"),
                        rs.getBigDecimal("average_alert_seconds").doubleValue()),
                since);
    }

    /**
     * Alert volume by type over the window.
     *
     * <p>Counting alerts rather than evaluations is the point: the engine evaluates constantly and
     * deduplicates, so an alert count is a count of distinct problems, not of measurements.
     */
    public Map<String, Integer> alertCountsByType(Duration window) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT type, COUNT(*)::int AS count
                FROM alert
                WHERE opened_at >= ?
                GROUP BY type
                ORDER BY count DESC
                """, since(window));

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("type"), (Integer) row.get("count"));
        }
        return counts;
    }

    /**
     * Incident volume and how long incidents took.
     *
     * <p>Time to acknowledge and time to resolve are measured from the incident's own timestamps, so
     * they describe what controllers actually did rather than what a rule believed.
     */
    public IncidentAnalytics incidents(Duration window) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*)::int AS total,
                    COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED', 'CANCELLED'))::int AS live,
                    COUNT(*) FILTER (WHERE status = 'RESOLVED')::int AS resolved,
                    COUNT(*) FILTER (WHERE status = 'CANCELLED')::int AS cancelled,
                    COUNT(*) FILTER (WHERE severity = 'CRITICAL')::int AS critical,
                    COALESCE(AVG(EXTRACT(EPOCH FROM (acknowledged_at - started_at)))
                             FILTER (WHERE acknowledged_at IS NOT NULL), 0)::numeric(10,1)
                        AS average_seconds_to_acknowledge,
                    COALESCE(AVG(EXTRACT(EPOCH FROM (resolved_at - started_at)))
                             FILTER (WHERE resolved_at IS NOT NULL), 0)::numeric(10,1)
                        AS average_seconds_to_resolve
                FROM incident
                WHERE started_at >= ?
                """,
                (rs, rowNum) -> new IncidentAnalytics(
                        rs.getInt("total"),
                        rs.getInt("live"),
                        rs.getInt("resolved"),
                        rs.getInt("cancelled"),
                        rs.getInt("critical"),
                        rs.getBigDecimal("average_seconds_to_acknowledge").doubleValue(),
                        rs.getBigDecimal("average_seconds_to_resolve").doubleValue()),
                since(window));
    }

    /**
     * Charging and battery state.
     *
     * <p>Charger utilisation is the share of chargers currently occupied, not an average over the
     * window: sessions are not sampled often enough for a time-weighted figure to mean anything yet.
     */
    public EvAnalytics ev(Duration window) {
        return jdbcTemplate.queryForObject("""
                WITH chargers AS (
                    SELECT
                        COUNT(*)::int AS total,
                        COUNT(*) FILTER (WHERE status = 'AVAILABLE')::int AS available,
                        COUNT(*) FILTER (WHERE status = 'OCCUPIED')::int AS occupied,
                        COUNT(*) FILTER (WHERE status IN ('OFFLINE', 'MAINTENANCE'))::int AS out_of_service
                    FROM charger
                ),
                sessions AS (
                    SELECT
                        COUNT(*)::int AS total,
                        COUNT(*) FILTER (WHERE status = 'ACTIVE')::int AS active,
                        COALESCE(AVG(EXTRACT(EPOCH FROM (ended_at - started_at)))
                                 FILTER (WHERE ended_at IS NOT NULL), 0)::numeric(10,1) AS average_seconds,
                        COALESCE(AVG(end_battery_percent - start_battery_percent)
                                 FILTER (WHERE end_battery_percent IS NOT NULL
                                           AND start_battery_percent IS NOT NULL), 0)::numeric(10,1)
                            AS average_percent_gained
                    FROM charging_session
                    WHERE started_at >= ?
                ),
                batteries AS (
                    SELECT
                        COALESCE(AVG(battery_percent), 0)::numeric(10,1) AS average_battery,
                        COUNT(*) FILTER (WHERE battery_percent <= 20)::int AS low_battery_vehicles
                    FROM vehicle_current_state
                    WHERE battery_percent IS NOT NULL
                )
                SELECT * FROM chargers, sessions, batteries
                """,
                (rs, rowNum) -> new EvAnalytics(
                        rs.getInt("total"),
                        rs.getInt("available"),
                        rs.getInt("occupied"),
                        rs.getInt("out_of_service"),
                        rs.getInt(5),
                        rs.getInt("active"),
                        rs.getBigDecimal("average_seconds").doubleValue(),
                        rs.getBigDecimal("average_percent_gained").doubleValue(),
                        rs.getBigDecimal("average_battery").doubleValue(),
                        rs.getInt("low_battery_vehicles")),
                since(window));
    }

    private OffsetDateTime since(Duration window) {
        return OffsetDateTime.ofInstant(Instant.now().minus(window), ZoneOffset.UTC);
    }
}
