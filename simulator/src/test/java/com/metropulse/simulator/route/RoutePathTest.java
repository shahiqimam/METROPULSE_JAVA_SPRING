package com.metropulse.simulator.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoutePathTest {

    /** The seeded M42 shape, west to east. */
    private final RoutePath route = new RoutePath(List.of(
            new GeoPoint(40.7128, -74.0060),
            new GeoPoint(40.7140, -74.0020),
            new GeoPoint(40.7152, -73.9980),
            new GeoPoint(40.7163, -73.9945),
            new GeoPoint(40.7178, -73.9900)));

    @Test
    void progressZeroIsTheFirstPointAndProgressOneIsTheLast() {
        assertThat(route.pointAt(0.0)).isEqualTo(new GeoPoint(40.7128, -74.0060));

        GeoPoint end = route.pointAt(1.0);
        assertThat(end.latitude()).isCloseTo(40.7178, within(1e-9));
        assertThat(end.longitude()).isCloseTo(-73.9900, within(1e-9));
    }

    @Test
    void progressIsClampedOutsideTheShape() {
        assertThat(route.pointAt(-0.5)).isEqualTo(route.pointAt(0.0));
        assertThat(route.pointAt(1.5)).isEqualTo(route.pointAt(1.0));
    }

    @Test
    void halfwayProgressIsHalfwayAlongTheShapeByDistance() {
        GeoPoint midpoint = route.pointAt(0.5);

        int samples = 500;
        double travelled = 0.0;
        GeoPoint cursor = route.pointAt(0.0);
        for (int sample = 1; sample <= samples; sample++) {
            GeoPoint next = route.pointAt(0.5 * sample / samples);
            travelled += RoutePath.distanceMeters(cursor, next);
            cursor = next;
        }

        assertThat(travelled).isCloseTo(route.lengthMeters() / 2.0, within(1.0));
        assertThat(RoutePath.distanceMeters(midpoint, cursor)).isLessThan(2.0);
    }

    @Test
    void shapeLengthIsAboutOnePointSixKilometres() {
        assertThat(route.lengthMeters()).isBetween(1_400.0, 1_800.0);
    }

    @Test
    void headingRunsRoughlyEastAlongTheShape() {
        assertThat(route.headingAt(0.1)).isBetween(60.0, 80.0);
        assertThat(route.headingAt(0.9)).isBetween(60.0, 80.0);
    }

    @Test
    void offsetMovesThePointByTheRequestedDistancePerpendicularToTravel() {
        GeoPoint onPath = route.pointAt(0.4);
        double heading = route.headingAt(0.4);

        GeoPoint offset = route.offsetFromPath(onPath, heading, 180.0);

        assertThat(RoutePath.distanceMeters(onPath, offset)).isCloseTo(180.0, within(1.0));
    }

    @Test
    void zeroOffsetLeavesThePointOnTheShape() {
        GeoPoint onPath = route.pointAt(0.4);

        assertThat(route.offsetFromPath(onPath, route.headingAt(0.4), 0.0)).isEqualTo(onPath);
    }

    @Test
    void aShapeNeedsAtLeastTwoPoints() {
        assertThatThrownBy(() -> new RoutePath(List.of(new GeoPoint(40.0, -74.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
