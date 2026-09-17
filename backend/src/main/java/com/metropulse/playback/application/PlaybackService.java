package com.metropulse.playback.application;

import com.metropulse.playback.api.CreatePlaybackSessionRequest;
import com.metropulse.playback.domain.PlaybackFrame;
import com.metropulse.playback.domain.PlaybackSession;
import com.metropulse.playback.domain.UnknownPlaybackSessionException;
import com.metropulse.telemetry.domain.UnknownVehicleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Replays stored telemetry.
 *
 * <h2>Playback is read-only, and that is the whole design</h2>
 *
 * <p>Nothing here writes to {@code vehicle_current_state}, publishes to Kafka, or feeds the alert
 * engine. A replay of last Tuesday must not make the control centre believe a bus is somewhere it
 * was last Tuesday, and a replay of a bunching incident must not raise the bunching alert again.
 *
 * <p>The only write is the session row itself, which records what was asked for so frames can be
 * paged by id. Frames are never copied out of {@code vehicle_telemetry}: duplicating history creates
 * a second version of the past that can drift from the first.
 */
@Service
public class PlaybackService {

    /** Longest window that can be replayed in one session. */
    public static final Duration MAX_WINDOW = Duration.ofHours(24);

    /** Most frames returned in one page. */
    public static final int MAX_PAGE_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    public PlaybackService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public PlaybackSession createSession(CreatePlaybackSessionRequest request, String createdBy) {
        if (!request.to().isAfter(request.from())) {
            throw new IllegalArgumentException("Playback window must end after it starts.");
        }
        if (Duration.between(request.from(), request.to()).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "Playback window cannot exceed " + MAX_WINDOW.toHours() + " hours.");
        }

        Long vehicleRowId = null;
        if (request.vehicleId() != null && !request.vehicleId().isBlank()) {
            vehicleRowId = findVehicleRowId(request.vehicleId());
            if (vehicleRowId == null) {
                throw new UnknownVehicleException(request.vehicleId());
            }
        }

        Long routeRowId = null;
        if (request.routeCode() != null && !request.routeCode().isBlank()) {
            routeRowId = jdbcTemplate.query(
                    "SELECT id FROM route WHERE code = ?",
                    rs -> rs.next() ? rs.getLong("id") : null,
                    request.routeCode());
        }

        int frameCount = countFrames(vehicleRowId, routeRowId, request.from(), request.to());

        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO playback_session (
                    vehicle_id, route_id, from_time, to_time, speed, frame_count, created_by
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                vehicleRowId,
                routeRowId,
                at(request.from()),
                at(request.to()),
                request.speedOrDefault(),
                frameCount,
                createdBy);

        return findSession(sessionId);
    }

    public PlaybackSession findSession(long sessionId) {
        List<PlaybackSession> sessions = jdbcTemplate.query("""
                SELECT
                    p.id, v.fleet_number, r.code AS route_code,
                    p.from_time, p.to_time, p.speed, p.frame_count, p.created_at, p.created_by
                FROM playback_session p
                LEFT JOIN vehicle v ON v.id = p.vehicle_id
                LEFT JOIN route r ON r.id = p.route_id
                WHERE p.id = ?
                """, this::mapSession, sessionId);

        if (sessions.isEmpty()) {
            throw new UnknownPlaybackSessionException(sessionId);
        }
        return sessions.getFirst();
    }

    /**
     * Reads a page of frames in the order they were recorded.
     *
     * <p>Paged rather than streamed whole: a busy hour of a full fleet is a lot of rows, and a client
     * scrubbing a timeline only needs the part it is showing.
     */
    public List<PlaybackFrame> findFrames(long sessionId, int offset, int limit) {
        PlaybackSession session = findSession(sessionId);
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

        return jdbcTemplate.query("""
                SELECT
                    v.fleet_number,
                    vt.recorded_at,
                    ST_Y(vt.location) AS latitude,
                    ST_X(vt.location) AS longitude,
                    vt.speed_kph,
                    vt.heading_degrees,
                    vt.occupancy_estimate,
                    vt.battery_percent
                FROM vehicle_telemetry vt
                JOIN vehicle v ON v.id = vt.vehicle_id
                WHERE vt.recorded_at >= ?
                  AND vt.recorded_at <= ?
                  AND (CAST(? AS varchar) IS NULL OR v.fleet_number = CAST(? AS varchar))
                  AND (CAST(? AS varchar) IS NULL OR v.assigned_route_id = (
                        SELECT id FROM route WHERE code = CAST(? AS varchar)
                  ))
                ORDER BY vt.recorded_at, v.fleet_number
                OFFSET ?
                LIMIT ?
                """,
                (rs, rowNum) -> new PlaybackFrame(
                        rs.getString("fleet_number"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"),
                        rs.getBigDecimal("speed_kph"),
                        rs.getBigDecimal("heading_degrees"),
                        rs.getInt("occupancy_estimate"),
                        (Integer) rs.getObject("battery_percent")),
                at(session.from()),
                at(session.to()),
                session.vehicleId(),
                session.vehicleId(),
                session.routeCode(),
                session.routeCode(),
                Math.max(offset, 0),
                pageSize);
    }

    private int countFrames(Long vehicleRowId, Long routeRowId, Instant from, Instant to) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM vehicle_telemetry vt
                JOIN vehicle v ON v.id = vt.vehicle_id
                WHERE vt.recorded_at >= ?
                  AND vt.recorded_at <= ?
                  AND (CAST(? AS bigint) IS NULL OR v.id = CAST(? AS bigint))
                  AND (CAST(? AS bigint) IS NULL OR v.assigned_route_id = CAST(? AS bigint))
                """,
                Integer.class,
                at(from), at(to), vehicleRowId, vehicleRowId, routeRowId, routeRowId);
        return count == null ? 0 : count;
    }

    private PlaybackSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new PlaybackSession(
                rs.getLong("id"),
                rs.getString("fleet_number"),
                rs.getString("route_code"),
                rs.getObject("from_time", OffsetDateTime.class).toInstant(),
                rs.getObject("to_time", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("speed").doubleValue(),
                rs.getInt("frame_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by"));
    }

    private Long findVehicleRowId(String fleetNumber) {
        return jdbcTemplate.query(
                "SELECT id FROM vehicle WHERE fleet_number = ?",
                rs -> rs.next() ? rs.getLong("id") : null,
                fleetNumber);
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
