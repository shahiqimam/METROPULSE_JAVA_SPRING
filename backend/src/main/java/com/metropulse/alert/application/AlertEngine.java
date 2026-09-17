package com.metropulse.alert.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metropulse.alert.domain.AlertCloseReason;
import com.metropulse.alert.domain.AlertSignal;
import com.metropulse.alert.domain.AlertStatus;
import com.metropulse.alert.domain.AlertType;
import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayQueryService;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which conditions are alerts.
 *
 * <p>Rules say what is true now; this decides what a controller is told, which is a different
 * question. The difference is entirely about time:
 *
 * <ul>
 *   <li>A condition that has just appeared is a <em>candidate</em>. It becomes an alert once it has
 *       held for its type's persistence window.</li>
 *   <li>A live alert whose condition disappears is not closed immediately. It enters recovery, and
 *       closes only if the condition stays away for the recovery window.</li>
 * </ul>
 *
 * <p>Both windows exist for the same reason: a measurement that sits near a threshold crosses it
 * repeatedly, and an engine without them produces a stream of alerts that controllers learn to
 * ignore. Ignored alerts are worse than no alerts.
 *
 * <p>Deduplication is by fingerprint, enforced by a unique index on live alerts rather than by
 * checking first and inserting after: two evaluations racing would both pass the check.
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final JdbcTemplate jdbcTemplate;
    private final TelemetryQueryService telemetryQueryService;
    private final HeadwayQueryService headwayQueryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AlertEngine(
            JdbcTemplate jdbcTemplate,
            TelemetryQueryService telemetryQueryService,
            HeadwayQueryService headwayQueryService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.telemetryQueryService = telemetryQueryService;
        this.headwayQueryService = headwayQueryService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${metropulse.alerts.evaluation-delay-ms:10000}")
    public void evaluateScheduled() {
        try {
            evaluate();
        } catch (RuntimeException ex) {
            // A scheduled method that throws can stop being scheduled; alerting should degrade, not die.
            log.warn("Alert evaluation failed.", ex);
        }
    }

    /**
     * Runs one evaluation.
     *
     * @return how many alerts are live afterwards
     */
    @Transactional
    public int evaluate() {
        Instant now = clock.instant();

        List<LatestVehicleTelemetry> vehicles = telemetryQueryService.findLatestVehicleTelemetry();
        List<HeadwayCondition> headwayConditions = headwayQueryService.findConditions();
        Set<String> liveFingerprints = liveFingerprints();

        List<AlertSignal> signals = AlertRules.evaluate(vehicles, headwayConditions, liveFingerprints);
        Set<String> signalled = new HashSet<>();

        for (AlertSignal signal : signals) {
            signalled.add(signal.fingerprint());
            apply(signal, now, liveFingerprints.contains(signal.fingerprint()));
        }

        clearCandidatesThatVanished(signalled);
        recoverAlertsWithoutSignals(signalled, now);

        return liveFingerprints().size();
    }

    /** Keeps a live alert fresh, or advances a candidate towards becoming one. */
    private void apply(AlertSignal signal, Instant now, boolean alreadyLive) {
        if (alreadyLive) {
            touchLiveAlert(signal, now);
            return;
        }

        Instant firstObservedAt = recordCandidate(signal, now);
        if (Duration.between(firstObservedAt, now).compareTo(signal.type().persistenceWindow()) >= 0) {
            openAlert(signal, firstObservedAt, now);
            jdbcTemplate.update("DELETE FROM alert_candidate WHERE fingerprint = ?", signal.fingerprint());
        }
    }

    /** Upserts the candidate and returns when this spell of the condition started. */
    private Instant recordCandidate(AlertSignal signal, Instant now) {
        OffsetDateTime timestamp = at(now);

        jdbcTemplate.update("""
                INSERT INTO alert_candidate (fingerprint, type, first_observed_at, last_observed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO UPDATE
                SET last_observed_at = EXCLUDED.last_observed_at
                """,
                signal.fingerprint(),
                signal.type().name(),
                timestamp,
                timestamp);

        OffsetDateTime firstObservedAt = jdbcTemplate.queryForObject(
                "SELECT first_observed_at FROM alert_candidate WHERE fingerprint = ?",
                OffsetDateTime.class,
                signal.fingerprint());

        return firstObservedAt == null ? now : firstObservedAt.toInstant();
    }

    /**
     * Opens an alert.
     *
     * <p>{@code opened_at} is when the condition started, not when the engine noticed it had lasted;
     * a controller asking "how long has this been going on" wants the former.
     */
    private void openAlert(AlertSignal signal, Instant firstObservedAt, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO alert (
                    type, fingerprint, severity, status, vehicle_id, route_id,
                    opened_at, last_observed_at, details
                )
                -- Every parameter is cast: in a SELECT list there is no column to infer a type from,
                -- and the vehicle/route lookups are outer joins so an alert can exist without either.
                SELECT
                    CAST(? AS varchar),
                    CAST(? AS varchar),
                    CAST(? AS varchar),
                    'OPEN',
                    v.id,
                    r.id,
                    CAST(? AS timestamptz),
                    CAST(? AS timestamptz),
                    CAST(? AS jsonb)
                FROM (SELECT 1) AS anchor
                LEFT JOIN vehicle v ON v.fleet_number = CAST(? AS varchar)
                LEFT JOIN route r ON r.code = CAST(? AS varchar)
                ON CONFLICT (fingerprint) WHERE status <> 'CLOSED' DO NOTHING
                """,
                signal.type().name(),
                signal.fingerprint(),
                signal.severity().name(),
                at(firstObservedAt),
                at(now),
                toJson(signal.details()),
                signal.vehicleId(),
                signal.routeCode());
    }

    /** The condition is still true: refresh it and cancel any recovery in progress. */
    private void touchLiveAlert(AlertSignal signal, Instant now) {
        jdbcTemplate.update("""
                UPDATE alert
                SET last_observed_at = ?,
                    recovering_since = NULL,
                    details = CAST(? AS jsonb),
                    severity = ?
                WHERE fingerprint = ?
                  AND status <> 'CLOSED'
                """,
                at(now),
                toJson(signal.details()),
                signal.severity().name(),
                signal.fingerprint());
    }

    /** A condition that vanished before it matured leaves no trace. */
    private void clearCandidatesThatVanished(Set<String> signalled) {
        if (signalled.isEmpty()) {
            jdbcTemplate.update("DELETE FROM alert_candidate");
            return;
        }

        jdbcTemplate.update(
                "DELETE FROM alert_candidate WHERE fingerprint NOT IN (" + placeholders(signalled) + ")",
                signalled.toArray());
    }

    /**
     * Starts or completes recovery for live alerts whose condition is no longer signalled.
     *
     * <p>Marking and closing are separate steps because they answer different questions: when did the
     * condition stop being seen, and has it been gone long enough. The window itself is read from the
     * alert type rather than encoded in SQL, so the rules live in one place.
     */
    private void recoverAlertsWithoutSignals(Set<String> signalled, Instant now) {
        String notSignalled = signalled.isEmpty()
                ? ""
                : " AND fingerprint NOT IN (" + placeholders(signalled) + ")";

        List<Object> markArguments = new ArrayList<>();
        markArguments.add(at(now));
        markArguments.addAll(signalled);

        jdbcTemplate.update(
                "UPDATE alert SET recovering_since = ? "
                        + "WHERE status <> 'CLOSED' AND recovering_since IS NULL" + notSignalled,
                markArguments.toArray());

        List<RecoveringAlert> recovering = jdbcTemplate.query(
                "SELECT id, type, recovering_since FROM alert "
                        + "WHERE status <> 'CLOSED' AND recovering_since IS NOT NULL" + notSignalled,
                (rs, rowNum) -> new RecoveringAlert(
                        rs.getLong("id"),
                        AlertType.valueOf(rs.getString("type")),
                        rs.getObject("recovering_since", OffsetDateTime.class).toInstant()),
                signalled.toArray());

        for (RecoveringAlert alert : recovering) {
            if (Duration.between(alert.recoveringSince(), now).compareTo(alert.type().recoveryWindow()) >= 0) {
                close(alert.id(), AlertCloseReason.RECOVERED, now);
            }
        }
    }

    /** Closes one alert. Used by recovery and by a controller closing it by hand. */
    public void close(long alertId, AlertCloseReason reason, Instant now) {
        jdbcTemplate.update("""
                UPDATE alert
                SET status = 'CLOSED',
                    closed_at = ?,
                    close_reason = ?
                WHERE id = ?
                  AND status <> 'CLOSED'
                """,
                at(now),
                reason.name(),
                alertId);
    }

    private static String placeholders(Set<String> values) {
        return String.join(",", values.stream().map(value -> "?").toList());
    }

    private record RecoveringAlert(long id, AlertType type, Instant recoveringSince) {
    }

    private Set<String> liveFingerprints() {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT fingerprint FROM alert WHERE status <> 'CLOSED'", String.class));
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize alert details.", ex);
        }
    }
}
