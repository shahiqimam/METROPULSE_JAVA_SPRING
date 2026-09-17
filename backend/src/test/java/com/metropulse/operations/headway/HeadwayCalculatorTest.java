package com.metropulse.operations.headway;

import com.metropulse.operations.headway.HeadwayCalculator.VehiclePosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HeadwayCalculatorTest {

    private static final double ROUTE_LENGTH_METERS = 1600.0;

    @Test
    void evenlySpacedVehiclesHaveEqualHeadways() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("BUS-1", 0.00, 36.0),
                new VehiclePosition("BUS-2", 0.25, 36.0),
                new VehiclePosition("BUS-3", 0.50, 36.0),
                new VehiclePosition("BUS-4", 0.75, 36.0)
        ), ROUTE_LENGTH_METERS);

        // A quarter of 1600 m is 400 m; at 36 kph (10 m/s) that is 40 seconds. Three pairs, not
        // four: the vehicle at the front of the route has nothing ahead of it.
        assertThat(pairs).hasSize(3);
        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.gapMeters()).isCloseTo(400.0, within(0.01));
            assertThat(pair.headwaySeconds()).isCloseTo(40.0, within(0.01));
        });
    }

    @Test
    void theLeaderOfEachVehicleIsTheOneAheadAlongTheShape() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("BUS-2", 0.50, 36.0),
                new VehiclePosition("BUS-1", 0.10, 36.0),
                new VehiclePosition("BUS-3", 0.90, 36.0)
        ), ROUTE_LENGTH_METERS);

        assertThat(pairs).extracting(HeadwayPair::followerVehicleId).containsExactly("BUS-1", "BUS-2");
        assertThat(pairs).extracting(HeadwayPair::leaderVehicleId).containsExactly("BUS-2", "BUS-3");
    }

    @Test
    void theVehicleAtTheFrontOfTheRouteHasNoHeadwayOfItsOwn() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("BUS-1", 0.10, 36.0),
                new VehiclePosition("BUS-2", 0.90, 36.0)
        ), ROUTE_LENGTH_METERS);

        // A trip ends at a terminal. Pairing the vehicle approaching it with one back at the start of
        // the route would invent a bunched pair out of two vehicles a route apart.
        assertThat(pairs).hasSize(1);
        assertThat(pairs.getFirst().followerVehicleId()).isEqualTo("BUS-1");
        assertThat(pairs.getFirst().leaderVehicleId()).isEqualTo("BUS-2");
        assertThat(pairs.getFirst().gapMeters()).isCloseTo(1_280.0, within(0.01));
    }

    @Test
    void aFollowerThatHasClosedOnItsLeaderHasAShortHeadway() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("LEADER", 0.52, 36.0),
                new VehiclePosition("FOLLOWER", 0.50, 36.0)
        ), ROUTE_LENGTH_METERS);

        HeadwayPair closed = pairs.stream()
                .filter(pair -> pair.followerVehicleId().equals("FOLLOWER"))
                .findFirst()
                .orElseThrow();

        assertThat(closed.gapMeters()).isCloseTo(32.0, within(0.01));
        assertThat(closed.headwaySeconds()).isCloseTo(3.2, within(0.01));
    }

    @Test
    void aStationaryFollowerIsMeasuredWithTheRoutesReferenceSpeed() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("LEADER", 0.50, 36.0),
                new VehiclePosition("DWELLING", 0.25, 0.0)
        ), ROUTE_LENGTH_METERS);

        HeadwayPair dwelling = pairFor(pairs, "DWELLING");

        // Dividing 400 m by the follower's own 0 kph says nothing; the route is moving at 36 kph.
        assertThat(dwelling.basis()).isEqualTo(HeadwayBasis.ROUTE_REFERENCE_SPEED);
        assertThat(dwelling.headwaySeconds()).isCloseTo(40.0, within(0.01));
        assertThat(dwelling.gapMeters()).isCloseTo(400.0, within(0.01));
    }

    @Test
    void aVehicleQueuedRightBehindItsLeaderReadsAsAVeryShortHeadway() {
        // The case this exists for: a follower held twelve meters behind its leader is bunched, and
        // its own speed of zero must not hide that.
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("LEADER", 0.5075, 28.0),
                new VehiclePosition("QUEUED", 0.5000, 0.0)
        ), ROUTE_LENGTH_METERS);

        HeadwayPair queued = pairFor(pairs, "QUEUED");

        assertThat(queued.gapMeters()).isCloseTo(12.0, within(0.5));
        assertThat(queued.headwaySeconds()).isNotNull().isLessThan(3.0);
        assertThat(queued.basis()).isEqualTo(HeadwayBasis.ROUTE_REFERENCE_SPEED);
    }

    @Test
    void speedAtTheMinimumIsStillTreatedAsNotMoving() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("LEADER", 0.50, 30.0),
                new VehiclePosition("CRAWLING", 0.25, HeadwayCalculator.MINIMUM_SPEED_KPH)
        ), ROUTE_LENGTH_METERS);

        assertThat(pairFor(pairs, "CRAWLING").basis()).isEqualTo(HeadwayBasis.ROUTE_REFERENCE_SPEED);
    }

    @Test
    void justAboveTheMinimumSpeedUsesTheFollowersOwnSpeed() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("LEADER", 0.50, 30.0),
                new VehiclePosition("MOVING", 0.25, HeadwayCalculator.MINIMUM_SPEED_KPH + 0.1)
        ), ROUTE_LENGTH_METERS);

        HeadwayPair moving = pairFor(pairs, "MOVING");
        assertThat(moving.basis()).isEqualTo(HeadwayBasis.FOLLOWER_SPEED);
        assertThat(moving.headwaySeconds()).isNotNull().isGreaterThan(0.0);
    }

    @Test
    void aRouteWhereNothingIsMovingHasNoHeadwayAtAll() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("STOPPED-1", 0.50, 0.0),
                new VehiclePosition("STOPPED-2", 0.25, 0.0)
        ), ROUTE_LENGTH_METERS);

        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.headwaySeconds()).isNull();
            assertThat(pair.basis()).isEqualTo(HeadwayBasis.UNKNOWN);
        });
    }

    @Test
    void theReferenceSpeedIsTheMedianOfTheVehiclesThatAreMoving() {
        List<HeadwayPair> pairs = HeadwayCalculator.calculate(List.of(
                new VehiclePosition("STOPPED", 0.10, 0.0),
                new VehiclePosition("SLOW", 0.35, 18.0),
                new VehiclePosition("MID", 0.60, 36.0),
                new VehiclePosition("FAST", 0.85, 54.0)
        ), ROUTE_LENGTH_METERS);

        // Median of 18, 36, 54 is 36 kph = 10 m/s; the stopped vehicle's gap is 0.25 of 1600 m.
        assertThat(pairFor(pairs, "STOPPED").headwaySeconds()).isCloseTo(40.0, within(0.01));
    }

    private HeadwayPair pairFor(List<HeadwayPair> pairs, String followerVehicleId) {
        return pairs.stream()
                .filter(pair -> pair.followerVehicleId().equals(followerVehicleId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void fewerThanTwoVehiclesHaveNoHeadwayAtAll() {
        assertThat(HeadwayCalculator.calculate(List.of(), ROUTE_LENGTH_METERS)).isEmpty();
        assertThat(HeadwayCalculator.calculate(
                List.of(new VehiclePosition("ALONE", 0.5, 30.0)), ROUTE_LENGTH_METERS)).isEmpty();
    }

    @Test
    void aRouteWithoutLengthProducesNoPairs() {
        assertThat(HeadwayCalculator.calculate(List.of(
                new VehiclePosition("BUS-1", 0.1, 30.0),
                new VehiclePosition("BUS-2", 0.6, 30.0)
        ), 0.0)).isEmpty();
    }
}
