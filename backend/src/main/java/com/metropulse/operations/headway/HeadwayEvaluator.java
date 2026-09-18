package com.metropulse.operations.headway;

import com.metropulse.operations.domain.ConnectivityState;
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
import java.util.Optional;
import java.util.Set;

/**
 * Evaluates the spacing of every route's vehicles and remembers which pairs are out of range.
 *
 * <p>Runs on a timer rather than per telemetry event: headway is a property of a whole route at an
 * instant, so re-evaluating it for every vehicle's event would repeat the same work with mostly the
 * same answer. The tick rate only has to be short relative to the rule's persistence window.
 *
 * <p>Vehicles whose telemetry has gone stale are excluded. Their last known position is not evidence
 * of where they are now, and treating it as such would invent bunching out of a vehicle that simply
 * stopped reporting.
 *
 * <p>The {@link Clock} is injected so tests can move time rather than wait for it.
 */
@Service
public class HeadwayEvaluator {

    private static final Logger log = LoggerFactory.getLogger(HeadwayEvaluator.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public HeadwayEvaluator(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${metropulse.operations.headway-evaluation-delay-ms:10000}")
    public void evaluateAllRoutes() {
        try {
            for (RouteContext route : activeRoutes()) {
                evaluateRoute(route);
            }
        } catch (RuntimeException ex) {
            // A scheduled method that throws stops being scheduled again in some configurations;
            // headway evaluation is not important enough to take down with it.
            log.warn("Headway evaluation failed.", ex);
        }
    }

    /**
     * Evaluates one route and reconciles its stored conditions.
     *
     * <p>Conditions that are still true have their observation window extended, ones that have held
     * long enough are confirmed, and ones that are no longer true are removed. Removal is how
     * recovery works: a pair that recovers has to build its window again from scratch if it
     * deteriorates later, rather than resuming a stale one.
     */
    @Transactional
    public List<HeadwayCondition> evaluateRoute(RouteContext route) {
        Instant now = clock.instant();
        List<HeadwayCalculator.VehiclePosition> positions = reportingVehicles(route.routeId());
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(positions, route.lengthMeters());

        Set<String> stillOutOfRange = new HashSet<>();
        List<HeadwayCondition> conditions = new ArrayList<>();

        for (HeadwayPair pair : pairs) {
            Optional<HeadwayConditionType> classification =
                    HeadwayRule.classify(pair.headwaySeconds(), route.targetHeadwaySeconds());
            if (classification.isEmpty()) {
                continue;
            }

            HeadwayConditionType type = classification.get();
            String fingerprint = pair.fingerprint(type, route.routeCode());
            stillOutOfRange.add(fingerprint);
            conditions.add(record(route, pair, type, fingerprint, now));
        }

        clearRecoveredConditions(route.routeId(), stillOutOfRange);
        return conditions;
    }

    /** Upserts the condition, keeping the earliest observation so the window measures the whole spell. */
    private HeadwayCondition record(
            RouteContext route,
            HeadwayPair pair,
            HeadwayConditionType type,
            String fingerprint,
            Instant now
    ) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        jdbcTemplate.update("""
                INSERT INTO headway_condition (
                    fingerprint,
                    route_id,
                    condition_type,
                    leader_vehicle_id,
                    follower_vehicle_id,
                    headway_seconds,
                    target_headway_seconds,
                    first_observed_at,
                    last_observed_at
                )
                SELECT ?, ?, ?, leader.id, follower.id, ?, ?, ?, ?
                FROM vehicle leader, vehicle follower
                WHERE leader.fleet_number = ?
                  AND follower.fleet_number = ?
                ON CONFLICT (fingerprint) DO UPDATE
                SET headway_seconds = EXCLUDED.headway_seconds,
                    target_headway_seconds = EXCLUDED.target_headway_seconds,
                    last_observed_at = EXCLUDED.last_observed_at
                """,
                fingerprint,
                route.routeId(),
                type.name(),
                pair.headwaySeconds(),
                route.targetHeadwaySeconds(),
                timestamp,
                timestamp,
                pair.leaderVehicleId(),
                pair.followerVehicleId());

        Instant firstObservedAt = firstObservedAt(fingerprint);
        Duration observedFor = Duration.between(firstObservedAt, now);
        boolean confirmed = HeadwayRule.hasPersisted(observedFor);

        if (confirmed) {
            jdbcTemplate.update("""
                    UPDATE headway_condition
                    SET confirmed_at = COALESCE(confirmed_at, ?)
                    WHERE fingerprint = ?
                    """,
                    timestamp,
                    fingerprint);
        }

        return new HeadwayCondition(
                fingerprint,
                route.routeCode(),
                type,
                pair.leaderVehicleId(),
                pair.followerVehicleId(),
                pair.headwaySeconds(),
                route.targetHeadwaySeconds(),
                firstObservedAt,
                observedFor,
                confirmed);
    }

    private void clearRecoveredConditions(long routeId, Set<String> stillOutOfRange) {
        if (stillOutOfRange.isEmpty()) {
            jdbcTemplate.update("DELETE FROM headway_condition WHERE route_id = ?", routeId);
            return;
        }

        String placeholders = String.join(",", stillOutOfRange.stream().map(value -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(routeId);
        arguments.addAll(stillOutOfRange);

        jdbcTemplate.update(
                "DELETE FROM headway_condition WHERE route_id = ? AND fingerprint NOT IN (" + placeholders + ")",
                arguments.toArray());
    }

    private Instant firstObservedAt(String fingerprint) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT first_observed_at FROM headway_condition WHERE fingerprint = ?",
                OffsetDateTime.class,
                fingerprint);
        return value == null ? clock.instant() : value.toInstant();
    }

    /**
     * Vehicles actually running the route, whose telemetry is fresh enough to place them.
     *
     * <p>The age cut-off is the same one the UI calls STALE, so a vehicle the controller can see is
     * not reporting is also not used to claim its followers are bunched.
     *
     * <p>Vehicles that have not left the origin are excluded. Several can be sitting at a terminal at
     * once between trips, all at the same point on the shape, and they are not bunched - they have not
     * started. Counting them produced pairs a few metres apart that would be classified as the most
     * severe bunching on the route, which is the phantom this whole design is meant to avoid.
     */
    private List<HeadwayCalculator.VehiclePosition> reportingVehicles(long routeId) {
        return jdbcTemplate.query("""
                SELECT
                    v.fleet_number,
                    vcs.route_progress,
                    vcs.speed_kph
                FROM vehicle_current_state vcs
                JOIN vehicle v ON v.id = vcs.vehicle_id
                WHERE vcs.route_id = ?
                  AND vcs.route_progress IS NOT NULL
                  AND vcs.route_progress > 0
                  AND EXTRACT(EPOCH FROM (now() - vcs.recorded_at)) <= ?
                """,
                (rs, rowNum) -> new HeadwayCalculator.VehiclePosition(
                        rs.getString("fleet_number"),
                        rs.getBigDecimal("route_progress").doubleValue(),
                        rs.getBigDecimal("speed_kph").doubleValue()),
                routeId,
                ConnectivityState.ONLINE_MAX_AGE_SECONDS);
    }

    /** Routes that can be evaluated, with the shape length PostGIS measures in meters. */
    public List<RouteContext> activeRoutes() {
        return jdbcTemplate.query("""
                SELECT
                    r.id,
                    r.code,
                    r.target_headway_seconds,
                    ST_Length(r.geometry::geography) AS length_meters
                FROM route r
                WHERE r.active
                ORDER BY r.code
                """,
                (rs, rowNum) -> new RouteContext(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getInt("target_headway_seconds"),
                        rs.getDouble("length_meters")));
    }

    public Optional<RouteContext> findRoute(String routeCode) {
        return activeRoutes().stream()
                .filter(route -> route.routeCode().equals(routeCode))
                .findFirst();
    }

    /** A route as the headway rules need it. */
    public record RouteContext(long routeId, String routeCode, int targetHeadwaySeconds, double lengthMeters) {
    }
}
