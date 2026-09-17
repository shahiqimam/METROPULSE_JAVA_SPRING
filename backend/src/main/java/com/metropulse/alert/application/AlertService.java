package com.metropulse.alert.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metropulse.alert.domain.AlertAlreadyClosedException;
import com.metropulse.alert.domain.AlertCloseReason;
import com.metropulse.alert.domain.AlertSeverity;
import com.metropulse.alert.domain.AlertStatus;
import com.metropulse.alert.domain.AlertType;
import com.metropulse.alert.domain.AlertView;
import com.metropulse.alert.domain.UnknownAlertException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads alerts and applies the actions a controller can take.
 *
 * <p>Controllers acknowledge and close; they do not set status directly. Keeping the transitions here
 * rather than letting the API write a status field is what makes "closed" always mean the same thing
 * and always carry a reason.
 */
@Service
public class AlertService {

    private final JdbcTemplate jdbcTemplate;
    private final AlertEngine alertEngine;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AlertService(
            JdbcTemplate jdbcTemplate,
            AlertEngine alertEngine,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertEngine = alertEngine;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Lists alerts, worst first.
     *
     * @param includeClosed false returns only what is still live, which is what a control screen wants
     */
    public List<AlertView> findAlerts(boolean includeClosed, Integer limit) {
        String sql = """
                SELECT
                    a.id,
                    a.type,
                    a.fingerprint,
                    a.severity,
                    a.status,
                    v.fleet_number,
                    r.code AS route_code,
                    a.opened_at,
                    a.last_observed_at,
                    a.recovering_since,
                    a.acknowledged_at,
                    a.acknowledged_by,
                    a.closed_at,
                    a.close_reason,
                    a.details::text AS details
                FROM alert a
                LEFT JOIN vehicle v ON v.id = a.vehicle_id
                LEFT JOIN route r ON r.id = a.route_id
                -- Cast so Postgres can type the parameter; it cannot infer boolean from context here.
                WHERE (CAST(? AS boolean) OR a.status <> 'CLOSED')
                ORDER BY
                    CASE a.status WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END,
                    CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'MAJOR' THEN 1 ELSE 2 END,
                    a.opened_at DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, this::mapAlert, includeClosed, limit == null ? 200 : limit);
    }

    public AlertView findAlert(long alertId) {
        List<AlertView> alerts = jdbcTemplate.query("""
                SELECT
                    a.id, a.type, a.fingerprint, a.severity, a.status,
                    v.fleet_number, r.code AS route_code,
                    a.opened_at, a.last_observed_at, a.recovering_since,
                    a.acknowledged_at, a.acknowledged_by, a.closed_at, a.close_reason,
                    a.details::text AS details
                FROM alert a
                LEFT JOIN vehicle v ON v.id = a.vehicle_id
                LEFT JOIN route r ON r.id = a.route_id
                WHERE a.id = ?
                """, this::mapAlert, alertId);

        if (alerts.isEmpty()) {
            throw new UnknownAlertException(alertId);
        }
        return alerts.getFirst();
    }

    /**
     * Marks an alert as seen by a controller.
     *
     * <p>Acknowledging does not close it: the condition is still true, and the engine will keep it
     * alive until it actually recovers. Acknowledging twice keeps the first acknowledgement, because
     * who saw it first is the useful fact.
     */
    @Transactional
    public AlertView acknowledge(long alertId, String controller) {
        AlertView alert = findAlert(alertId);
        if (alert.status() == AlertStatus.CLOSED) {
            throw new AlertAlreadyClosedException(alertId);
        }

        jdbcTemplate.update("""
                UPDATE alert
                SET status = 'ACKNOWLEDGED',
                    acknowledged_at = COALESCE(acknowledged_at, ?),
                    acknowledged_by = COALESCE(acknowledged_by, ?)
                WHERE id = ?
                  AND status <> 'CLOSED'
                """,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                controller,
                alertId);

        return findAlert(alertId);
    }

    /**
     * Closes an alert by hand.
     *
     * <p>The condition may still be true, so the engine can legitimately raise the same fingerprint
     * again on its next evaluation. That is correct: a controller closing an alert says "I have dealt
     * with this", not "this can never happen again".
     */
    @Transactional
    public AlertView close(long alertId) {
        AlertView alert = findAlert(alertId);
        if (alert.status() == AlertStatus.CLOSED) {
            throw new AlertAlreadyClosedException(alertId);
        }

        alertEngine.close(alertId, AlertCloseReason.CLOSED_BY_CONTROLLER, clock.instant());
        return findAlert(alertId);
    }

    /** Counts of live alerts by severity, for the control centre's summary. */
    public Map<String, Integer> liveCountsBySeverity() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT severity, COUNT(*)::int AS count
                FROM alert
                WHERE status <> 'CLOSED'
                GROUP BY severity
                """);

        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (AlertSeverity severity : AlertSeverity.values()) {
            counts.put(severity.name(), 0);
        }
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("severity"), (Integer) row.get("count"));
        }
        return counts;
    }

    private AlertView mapAlert(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AlertView(
                rs.getLong("id"),
                AlertType.valueOf(rs.getString("type")),
                rs.getString("fingerprint"),
                AlertSeverity.valueOf(rs.getString("severity")),
                AlertStatus.valueOf(rs.getString("status")),
                rs.getString("fleet_number"),
                rs.getString("route_code"),
                instant(rs.getObject("opened_at", OffsetDateTime.class)),
                instant(rs.getObject("last_observed_at", OffsetDateTime.class)),
                instant(rs.getObject("recovering_since", OffsetDateTime.class)),
                instant(rs.getObject("acknowledged_at", OffsetDateTime.class)),
                rs.getString("acknowledged_by"),
                instant(rs.getObject("closed_at", OffsetDateTime.class)),
                rs.getString("close_reason") == null
                        ? null
                        : AlertCloseReason.valueOf(rs.getString("close_reason")),
                readDetails(rs.getString("details")));
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private Map<String, Object> readDetails(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            // Details are context, not the alert itself; an unreadable blob must not hide the alert.
            return Map.of("unreadableDetails", json);
        }
    }

    /** Alert ids currently live for a vehicle, used when building the vehicle view. */
    public List<AlertView> findLiveAlertsForVehicle(String fleetNumber) {
        List<AlertView> alerts = new ArrayList<>(findAlerts(false, 500));
        alerts.removeIf(alert -> !fleetNumber.equals(alert.vehicleId()));
        return alerts;
    }
}
