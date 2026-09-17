package com.metropulse.schedule;

import com.metropulse.schedule.read.RouteGeometryPoint;
import com.metropulse.schedule.read.RouteStop;
import com.metropulse.schedule.read.RouteSummary;
import com.metropulse.schedule.read.ScheduleQueryService;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Test
    void routesReportTheirStopAndTripCounts() {
        List<RouteSummary> routes = scheduleQueryService.findRoutes();

        assertThat(routes).hasSize(1);
        RouteSummary route = routes.getFirst();
        assertThat(route.code()).isEqualTo("M42");
        assertThat(route.agencyName()).isEqualTo("MetroPulse Transit Authority");
        assertThat(route.stopCount()).isEqualTo(5);
        assertThat(route.tripCount()).isEqualTo(1);
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
    void stopsAreReturnedInStopSequenceOrder() {
        List<RouteStop> stops = scheduleQueryService.findRouteStops("M42");

        assertThat(stops).extracting(RouteStop::stopSequence).containsExactly(1, 2, 3, 4, 5);
        assertThat(stops.getFirst().stopName()).isEqualTo("West Terminal");
        assertThat(stops.getLast().stopName()).isEqualTo("East Terminal");
        assertThat(stops.getFirst().plannedArrivalSeconds())
                .isLessThan(stops.getLast().plannedArrivalSeconds());
    }
}
