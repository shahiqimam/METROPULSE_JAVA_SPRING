package com.metropulse.operations.headway;

import com.metropulse.operations.domain.ConnectivityState;
import com.metropulse.operations.headway.RouteHeadwaySnapshot.HeadwayPairView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads current spacing for the control centre.
 *
 * <p>Pairs are computed from stored state on read, because they are a view of the present and go out
 * of date the moment a vehicle moves. Conditions are read from the table instead, because they carry
 * the history the rules depend on: when the spell started and whether it has lasted long enough.
 */
@Service
public class HeadwayQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final HeadwayEvaluator headwayEvaluator;
    private final Clock clock;

    public HeadwayQueryService(JdbcTemplate jdbcTemplate, HeadwayEvaluator headwayEvaluator, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.headwayEvaluator = headwayEvaluator;
        this.clock = clock;
    }

    public Optional<RouteHeadwaySnapshot> findRouteHeadway(String routeCode) {
        return headwayEvaluator.findRoute(routeCode).map(route -> {
            List<HeadwayCalculator.VehiclePosition> positions = reportingVehicles(route.routeId());
            List<HeadwayPair> pairs = HeadwayCalculator.calculate(positions, route.lengthMeters());

            return new RouteHeadwaySnapshot(
                    route.routeCode(),
                    route.targetHeadwaySeconds(),
                    route.lengthMeters(),
                    positions.size(),
                    pairs.stream().map(pair -> toView(pair, route.targetHeadwaySeconds())).toList(),
                    findConditions(route.routeCode()));
        });
    }

    public List<HeadwayCondition> findConditions() {
        return findConditions(null);
    }

    private List<HeadwayCondition> findConditions(String routeCode) {
        Instant now = clock.instant();

        return jdbcTemplate.query("""
                SELECT
                    hc.fingerprint,
                    r.code AS route_code,
                    hc.condition_type,
                    leader.fleet_number AS leader_fleet_number,
                    follower.fleet_number AS follower_fleet_number,
                    hc.headway_seconds,
                    hc.target_headway_seconds,
                    hc.first_observed_at,
                    hc.confirmed_at
                FROM headway_condition hc
                JOIN route r ON r.id = hc.route_id
                JOIN vehicle leader ON leader.id = hc.leader_vehicle_id
                JOIN vehicle follower ON follower.id = hc.follower_vehicle_id
                -- Cast both: with a null route filter Postgres has nothing to infer the type from,
                -- which fails at parse time rather than returning every route.
                WHERE (CAST(? AS varchar) IS NULL OR r.code = CAST(? AS varchar))
                ORDER BY hc.confirmed_at NULLS LAST, hc.first_observed_at
                """,
                (rs, rowNum) -> {
                    Instant firstObservedAt = rs.getObject("first_observed_at", OffsetDateTime.class).toInstant();
                    return new HeadwayCondition(
                            rs.getString("fingerprint"),
                            rs.getString("route_code"),
                            HeadwayConditionType.valueOf(rs.getString("condition_type")),
                            rs.getString("leader_fleet_number"),
                            rs.getString("follower_fleet_number"),
                            rs.getBigDecimal("headway_seconds").doubleValue(),
                            rs.getInt("target_headway_seconds"),
                            firstObservedAt,
                            Duration.between(firstObservedAt, now),
                            rs.getObject("confirmed_at") != null);
                },
                routeCode,
                routeCode);
    }

    private HeadwayPairView toView(HeadwayPair pair, int targetHeadwaySeconds) {
        Double ratio = pair.headwaySeconds() == null
                ? null
                : pair.headwaySeconds() / targetHeadwaySeconds;

        return new HeadwayPairView(
                pair.leaderVehicleId(),
                pair.followerVehicleId(),
                pair.gapMeters(),
                pair.headwaySeconds(),
                ratio,
                HeadwayRule.classify(pair.headwaySeconds(), targetHeadwaySeconds)
                        .map(Enum::name)
                        .orElse(pair.headwaySeconds() == null ? "UNKNOWN" : "NOMINAL"),
                pair.basis());
    }

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
}
