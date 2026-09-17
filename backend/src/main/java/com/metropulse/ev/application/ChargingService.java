package com.metropulse.ev.application;

import com.metropulse.ev.domain.ChargerNotAvailableException;
import com.metropulse.ev.domain.ChargerStatus;
import com.metropulse.ev.domain.ChargerView;
import com.metropulse.ev.domain.ChargingSessionStatus;
import com.metropulse.ev.domain.ChargingSessionView;
import com.metropulse.ev.domain.UnknownChargerException;
import com.metropulse.ev.domain.UnknownChargingSessionException;
import com.metropulse.ev.domain.VehicleAlreadyChargingException;
import com.metropulse.telemetry.domain.UnknownVehicleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Charger reservation and charging sessions.
 *
 * <h2>Why a pessimistic lock</h2>
 *
 * <p>Two controllers sending vehicles to the last free charger is not an unlikely race; it is what
 * happens at shift change. The obvious implementation — check the charger is AVAILABLE, then insert a
 * session — is wrong: both requests read AVAILABLE before either writes, and both proceed.
 *
 * <p>So the reservation takes a row lock on the charger first:
 *
 * <pre>
 *   SELECT ... FROM charger WHERE id = ? FOR UPDATE
 * </pre>
 *
 * <p>The second transaction blocks at that line until the first commits, and then reads the charger
 * as OCCUPIED rather than AVAILABLE. One reservation succeeds, the other gets a clean
 * {@code CHARGER_NOT_AVAILABLE} rather than a constraint violation or a duplicate session.
 *
 * <p>The unique partial indexes on active sessions stay as the database's own statement of the rule.
 * The lock exists to produce a good error; the constraints exist so the rule holds even if some
 * future code path forgets to lock.
 */
@Service
public class ChargingService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ChargingService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Reserves a charger for a vehicle and starts its session.
     *
     * <p>READ_COMMITTED is enough here because the lock, not the isolation level, is what serialises
     * the two racing transactions.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChargingSessionView startSession(String chargerCode, String vehicleId, String controller) {
        Long vehicleRowId = findVehicleRowId(vehicleId);
        if (vehicleRowId == null) {
            throw new UnknownVehicleException(vehicleId);
        }

        // Lock first, then decide. Reading the status before taking the lock would be reading a value
        // that another transaction is free to change before this one writes.
        LockedCharger charger = lockCharger(chargerCode);

        if (charger.status() != ChargerStatus.AVAILABLE) {
            throw new ChargerNotAvailableException(chargerCode, charger.status());
        }

        if (hasActiveSession(vehicleRowId)) {
            throw new VehicleAlreadyChargingException(vehicleId);
        }

        Instant now = clock.instant();
        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO charging_session (
                    vehicle_id, charger_id, status, started_at, start_battery_percent, started_by
                )
                SELECT ?, ?, 'ACTIVE', ?, vcs.battery_percent, ?
                FROM (SELECT 1) AS anchor
                LEFT JOIN vehicle_current_state vcs ON vcs.vehicle_id = ?
                RETURNING id
                """,
                Long.class,
                vehicleRowId,
                charger.id(),
                at(now),
                controller,
                vehicleRowId);

        jdbcTemplate.update("""
                UPDATE charger
                SET status = 'OCCUPIED',
                    version = version + 1,
                    updated_at = ?
                WHERE id = ?
                """,
                at(now),
                charger.id());

        return findSession(sessionId);
    }

    /**
     * Ends a session and frees its charger.
     *
     * <p>The charger is released whether the session completed or was interrupted: a charger nobody is
     * plugged into is available, and the reason the session ended is a property of the session.
     */
    @Transactional
    public ChargingSessionView endSession(long sessionId, ChargingSessionStatus endStatus) {
        if (endStatus == ChargingSessionStatus.ACTIVE) {
            throw new IllegalArgumentException("A session cannot be ended as ACTIVE.");
        }

        ChargingSessionView session = findSession(sessionId);
        if (session.status() != ChargingSessionStatus.ACTIVE) {
            throw new IllegalStateException("Charging session " + sessionId + " is already " + session.status() + ".");
        }

        Instant now = clock.instant();

        jdbcTemplate.update("""
                UPDATE charging_session
                SET status = ?,
                    ended_at = ?,
                    end_battery_percent = (
                        SELECT vcs.battery_percent
                        FROM vehicle_current_state vcs
                        WHERE vcs.vehicle_id = charging_session.vehicle_id
                    )
                WHERE id = ?
                """,
                endStatus.name(),
                at(now),
                sessionId);

        jdbcTemplate.update("""
                UPDATE charger
                SET status = 'AVAILABLE',
                    version = version + 1,
                    updated_at = ?
                WHERE id = (SELECT charger_id FROM charging_session WHERE id = ?)
                  AND status = 'OCCUPIED'
                """,
                at(now),
                sessionId);

        return findSession(sessionId);
    }

    /** Takes the row lock. Blocks until any transaction holding it commits or rolls back. */
    private LockedCharger lockCharger(String chargerCode) {
        List<LockedCharger> chargers = jdbcTemplate.query("""
                SELECT id, code, status
                FROM charger
                WHERE code = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new LockedCharger(
                        rs.getLong("id"),
                        rs.getString("code"),
                        ChargerStatus.valueOf(rs.getString("status"))),
                chargerCode);

        if (chargers.isEmpty()) {
            throw new UnknownChargerException(chargerCode);
        }
        return chargers.getFirst();
    }

    private boolean hasActiveSession(long vehicleRowId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_session WHERE vehicle_id = ? AND status = 'ACTIVE'",
                Integer.class,
                vehicleRowId);
        return count != null && count > 0;
    }

    public List<ChargerView> findChargers() {
        return jdbcTemplate.query("""
                SELECT
                    c.id,
                    c.code,
                    d.code AS depot_code,
                    d.name AS depot_name,
                    c.power_kw,
                    c.status,
                    v.fleet_number AS occupying_vehicle
                FROM charger c
                JOIN depot d ON d.id = c.depot_id
                LEFT JOIN charging_session s ON s.charger_id = c.id AND s.status = 'ACTIVE'
                LEFT JOIN vehicle v ON v.id = s.vehicle_id
                ORDER BY d.code, c.code
                """,
                (rs, rowNum) -> new ChargerView(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("depot_code"),
                        rs.getString("depot_name"),
                        rs.getBigDecimal("power_kw"),
                        ChargerStatus.valueOf(rs.getString("status")),
                        rs.getString("occupying_vehicle")));
    }

    public List<ChargingSessionView> findSessions(boolean activeOnly) {
        return jdbcTemplate.query("""
                SELECT
                    s.id, v.fleet_number, c.code AS charger_code, d.code AS depot_code,
                    s.status, s.started_at, s.ended_at,
                    s.start_battery_percent, s.end_battery_percent, s.started_by
                FROM charging_session s
                JOIN vehicle v ON v.id = s.vehicle_id
                JOIN charger c ON c.id = s.charger_id
                JOIN depot d ON d.id = c.depot_id
                WHERE (NOT CAST(? AS boolean) OR s.status = 'ACTIVE')
                ORDER BY s.started_at DESC
                LIMIT 200
                """, this::mapSession, activeOnly);
    }

    public ChargingSessionView findSession(long sessionId) {
        List<ChargingSessionView> sessions = jdbcTemplate.query("""
                SELECT
                    s.id, v.fleet_number, c.code AS charger_code, d.code AS depot_code,
                    s.status, s.started_at, s.ended_at,
                    s.start_battery_percent, s.end_battery_percent, s.started_by
                FROM charging_session s
                JOIN vehicle v ON v.id = s.vehicle_id
                JOIN charger c ON c.id = s.charger_id
                JOIN depot d ON d.id = c.depot_id
                WHERE s.id = ?
                """, this::mapSession, sessionId);

        if (sessions.isEmpty()) {
            throw new UnknownChargingSessionException(sessionId);
        }
        return sessions.getFirst();
    }

    private ChargingSessionView mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new ChargingSessionView(
                rs.getLong("id"),
                rs.getString("fleet_number"),
                rs.getString("charger_code"),
                rs.getString("depot_code"),
                ChargingSessionStatus.valueOf(rs.getString("status")),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ended_at", OffsetDateTime.class) == null
                        ? null
                        : rs.getObject("ended_at", OffsetDateTime.class).toInstant(),
                (Integer) rs.getObject("start_battery_percent"),
                (Integer) rs.getObject("end_battery_percent"),
                rs.getString("started_by"));
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

    private record LockedCharger(long id, String code, ChargerStatus status) {
    }
}
