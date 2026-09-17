package com.metropulse.incident.application;

import com.metropulse.incident.api.OpenIncidentRequest;
import com.metropulse.incident.domain.IncidentSeverity;
import com.metropulse.incident.domain.IncidentStatus;
import com.metropulse.incident.domain.IncidentTimelineEntry;
import com.metropulse.incident.domain.IncidentType;
import com.metropulse.incident.domain.IncidentView;
import com.metropulse.incident.domain.InvalidIncidentTransitionException;
import com.metropulse.incident.domain.UnknownIncidentException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The incident workflow.
 *
 * <p>Every state change goes through a named method — acknowledge, start mitigation, resolve,
 * cancel — and each one asks {@link IncidentStatus} whether the move is legal before making it.
 * There is deliberately no "set status" operation: an API that accepted a status would let a stale
 * screen or a typo put an incident anywhere, and the timeline would stop being a reliable account of
 * what happened.
 *
 * <p>The transition and its timeline entry are written in one transaction. An incident whose state
 * changed without a record of who changed it is worse than one that did not change at all, because
 * it looks authoritative.
 */
@Service
public class IncidentService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public IncidentService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public IncidentView open(OpenIncidentRequest request, String controller) {
        Instant now = clock.instant();
        String incidentNumber = nextIncidentNumber();

        Long incidentId = jdbcTemplate.queryForObject("""
                INSERT INTO incident (
                    incident_number, type, severity, status, title, description,
                    vehicle_id, route_id, opened_by, started_at
                )
                SELECT
                    CAST(? AS varchar),
                    CAST(? AS varchar),
                    CAST(? AS varchar),
                    'OPEN',
                    CAST(? AS varchar),
                    CAST(? AS text),
                    v.id,
                    r.id,
                    CAST(? AS varchar),
                    CAST(? AS timestamptz)
                FROM (SELECT 1) AS anchor
                LEFT JOIN vehicle v ON v.fleet_number = CAST(? AS varchar)
                LEFT JOIN route r ON r.code = CAST(? AS varchar)
                RETURNING id
                """,
                Long.class,
                incidentNumber,
                request.type().name(),
                request.severity().name(),
                request.title(),
                request.description(),
                controller,
                at(now),
                request.vehicleId(),
                request.routeCode());

        recordTimeline(incidentId, "TRANSITION", null, IncidentStatus.OPEN,
                request.description(), controller, now);

        return findIncident(incidentId);
    }

    /** A controller takes ownership. Assignment and acknowledgement are the same act. */
    @Transactional
    public IncidentView acknowledge(long incidentId, String note, String controller) {
        return transition(incidentId, IncidentStatus.ACKNOWLEDGED, note, controller,
                "acknowledged_at = ?, assigned_controller = COALESCE(assigned_controller, ?)",
                List.of(controller));
    }

    @Transactional
    public IncidentView startMitigation(long incidentId, String note, String controller) {
        return transition(incidentId, IncidentStatus.MITIGATING, note, controller,
                "mitigating_at = ?, assigned_controller = COALESCE(assigned_controller, ?)",
                List.of(controller));
    }

    @Transactional
    public IncidentView resolve(long incidentId, String note, String controller) {
        return transition(incidentId, IncidentStatus.RESOLVED, note, controller,
                "resolved_at = ?", List.of());
    }

    @Transactional
    public IncidentView cancel(long incidentId, String note, String controller) {
        return transition(incidentId, IncidentStatus.CANCELLED, note, controller,
                "cancelled_at = ?", List.of());
    }

    /**
     * Adds a note without changing state.
     *
     * <p>Allowed on a resolved incident: writing down what was learned afterwards is normal, and the
     * note is timestamped, so it cannot be mistaken for something said at the time.
     */
    @Transactional
    public IncidentView addNote(long incidentId, String note, String controller) {
        IncidentView incident = findIncident(incidentId);
        recordTimeline(incident.id(), "NOTE", null, null, note, controller, clock.instant());
        return findIncident(incidentId);
    }

    /**
     * Applies one transition.
     *
     * @param timestampClause the status-specific column to stamp, with its own parameters
     */
    private IncidentView transition(
            long incidentId,
            IncidentStatus target,
            String note,
            String controller,
            String timestampClause,
            List<Object> extraArguments
    ) {
        IncidentView incident = findIncident(incidentId);

        if (!incident.status().canTransitionTo(target)) {
            throw new InvalidIncidentTransitionException(incident.incidentNumber(), incident.status(), target);
        }

        Instant now = clock.instant();
        java.util.List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(target.name());
        arguments.add(at(now));
        arguments.addAll(extraArguments);
        arguments.add(incidentId);

        jdbcTemplate.update(
                "UPDATE incident SET status = ?, " + timestampClause + " WHERE id = ?",
                arguments.toArray());

        recordTimeline(incidentId, "TRANSITION", incident.status(), target, note, controller, now);
        return findIncident(incidentId);
    }

    private void recordTimeline(
            long incidentId,
            String entryType,
            IncidentStatus fromStatus,
            IncidentStatus toStatus,
            String note,
            String actor,
            Instant recordedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO incident_timeline (
                    incident_id, entry_type, from_status, to_status, note, actor, recorded_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                incidentId,
                entryType,
                fromStatus == null ? null : fromStatus.name(),
                toStatus == null ? null : toStatus.name(),
                note,
                actor,
                at(recordedAt));
    }

    public List<IncidentView> findIncidents(boolean includeClosed) {
        List<IncidentView> incidents = jdbcTemplate.query("""
                SELECT
                    i.id, i.incident_number, i.type, i.severity, i.status, i.title, i.description,
                    v.fleet_number, r.code AS route_code, i.opened_by, i.assigned_controller,
                    i.started_at, i.acknowledged_at, i.mitigating_at, i.resolved_at, i.cancelled_at
                FROM incident i
                LEFT JOIN vehicle v ON v.id = i.vehicle_id
                LEFT JOIN route r ON r.id = i.route_id
                WHERE (CAST(? AS boolean) OR i.status NOT IN ('RESOLVED', 'CANCELLED'))
                ORDER BY
                    CASE i.severity WHEN 'CRITICAL' THEN 0 WHEN 'MAJOR' THEN 1 ELSE 2 END,
                    i.started_at DESC
                """, this::mapIncident, includeClosed);

        // Timelines are fetched per incident rather than joined, so one incident with a long history
        // does not multiply every other row in the result.
        return incidents.stream()
                .map(incident -> withTimeline(incident, findTimeline(incident.id())))
                .toList();
    }

    public IncidentView findIncident(long incidentId) {
        List<IncidentView> incidents = jdbcTemplate.query("""
                SELECT
                    i.id, i.incident_number, i.type, i.severity, i.status, i.title, i.description,
                    v.fleet_number, r.code AS route_code, i.opened_by, i.assigned_controller,
                    i.started_at, i.acknowledged_at, i.mitigating_at, i.resolved_at, i.cancelled_at
                FROM incident i
                LEFT JOIN vehicle v ON v.id = i.vehicle_id
                LEFT JOIN route r ON r.id = i.route_id
                WHERE i.id = ?
                """, this::mapIncident, incidentId);

        if (incidents.isEmpty()) {
            throw new UnknownIncidentException(incidentId);
        }
        return withTimeline(incidents.getFirst(), findTimeline(incidentId));
    }

    private List<IncidentTimelineEntry> findTimeline(long incidentId) {
        return jdbcTemplate.query("""
                SELECT id, entry_type, from_status, to_status, note, actor, recorded_at
                FROM incident_timeline
                WHERE incident_id = ?
                ORDER BY recorded_at, id
                """,
                (rs, rowNum) -> new IncidentTimelineEntry(
                        rs.getLong("id"),
                        rs.getString("entry_type"),
                        status(rs.getString("from_status")),
                        status(rs.getString("to_status")),
                        rs.getString("note"),
                        rs.getString("actor"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()),
                incidentId);
    }

    private String nextIncidentNumber() {
        Long sequence = jdbcTemplate.queryForObject("SELECT nextval('incident_number_seq')", Long.class);
        return "INC-" + sequence;
    }

    private IncidentView mapIncident(ResultSet rs, int rowNum) throws SQLException {
        return new IncidentView(
                rs.getLong("id"),
                rs.getString("incident_number"),
                IncidentType.valueOf(rs.getString("type")),
                IncidentSeverity.valueOf(rs.getString("severity")),
                IncidentStatus.valueOf(rs.getString("status")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("fleet_number"),
                rs.getString("route_code"),
                rs.getString("opened_by"),
                rs.getString("assigned_controller"),
                instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("acknowledged_at", OffsetDateTime.class)),
                instant(rs.getObject("mitigating_at", OffsetDateTime.class)),
                instant(rs.getObject("resolved_at", OffsetDateTime.class)),
                instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
                List.of());
    }

    private IncidentView withTimeline(IncidentView incident, List<IncidentTimelineEntry> timeline) {
        return new IncidentView(
                incident.id(), incident.incidentNumber(), incident.type(), incident.severity(),
                incident.status(), incident.title(), incident.description(), incident.vehicleId(),
                incident.routeCode(), incident.openedBy(), incident.assignedController(),
                incident.startedAt(), incident.acknowledgedAt(), incident.mitigatingAt(),
                incident.resolvedAt(), incident.cancelledAt(), timeline);
    }

    private IncidentStatus status(String value) {
        return value == null ? null : IncidentStatus.valueOf(value);
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
