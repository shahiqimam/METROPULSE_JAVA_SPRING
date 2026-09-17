package com.metropulse.schedule;

import com.metropulse.schedule.read.RouteGeometryPoint;
import com.metropulse.schedule.read.RouteStop;
import com.metropulse.schedule.read.RouteSummary;
import com.metropulse.schedule.read.ScheduleQueryService;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads of the seeded static schedule.
 *
 * <p>The geometry assertions matter beyond the API surface: the map draws this shape, and the same
 * shape is what route progress and route deviation are measured against. If the two ever diverged,
 * vehicles would appear off a line they are actually on.
 */
class ScheduleQueryIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private ScheduleQueryService scheduleQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void routesReportTheirStopAndTripCounts() {
        // Assert on the seeded route by code rather than on how many routes exist: other tests import
        // their own, and a count assertion would make this fail for reasons unrelated to what it tests.
        RouteSummary route = scheduleQueryService.findRoutes().stream()
                .filter(summary -> summary.code().equals("M42"))
                .findFirst()
                .orElseThrow();

        assertThat(route.agencyName()).isEqualTo("MetroPulse Transit Authority");
        assertThat(route.stopCount()).isEqualTo(5);
        // A full weekday of service: every two minutes from 05:00 to 23:00.
        assertThat(route.tripCount()).isEqualTo(541);
        assertThat(route.routePointCount()).isEqualTo(5);
        assertThat(route.active()).isTrue();
    }

    @Test
    void routeGeometryIsReturnedInOrderAlongTheLine() {
        List<RouteGeometryPoint> geometry = scheduleQueryService.findRouteGeometry("M42");

        assertThat(geometry).hasSize(5);
        assertThat(geometry).extracting(RouteGeometryPoint::sequence).containsExactly(1, 2, 3, 4, 5);

        assertThat(geometry.getFirst().latitude()).isEqualByComparingTo("40.7128");
        assertThat(geometry.getFirst().longitude()).isEqualByComparingTo("-74.0060");
        assertThat(geometry.getLast().latitude()).isEqualByComparingTo("40.7178");
        assertThat(geometry.getLast().longitude()).isEqualByComparingTo("-73.9900");

        // The seeded shape runs west to east, so longitude increases along the line.
        assertThat(geometry).extracting(point -> point.longitude().doubleValue()).isSorted();
    }

    @Test
    void anUnknownRouteHasNoGeometry() {
        assertThat(scheduleQueryService.findRouteGeometry("NOPE")).isEmpty();
    }

    @Test
    void everySeededTripHasTheFullStopPattern() {
        // Schedule adherence is only measurable if every trip has stop times to be measured against.
        Integer tripsWithoutCalls = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int
                FROM trip t
                JOIN route r ON r.id = t.route_id
                WHERE r.code = 'M42'
                  AND NOT EXISTS (SELECT 1 FROM stop_time st WHERE st.trip_id = t.id)
                """, Integer.class);

        assertThat(tripsWithoutCalls).isZero();
    }

    @Test
    void stopsAreReturnedInStopSequenceOrder() {
        List<RouteStop> stops = scheduleQueryService.findRouteStops("M42");

        assertThat(stops).extracting(RouteStop::stopSequence).containsExactly(1, 2, 3, 4, 5);
        assertThat(stops.getFirst().stopName()).isEqualTo("West Terminal");
        assertThat(stops.getLast().stopName()).isEqualTo("East Terminal");
        assertThat(stops.getFirst().plannedArrivalSeconds())
                .isLessThan(stops.getLast().plannedArrivalSeconds());
    }
}
