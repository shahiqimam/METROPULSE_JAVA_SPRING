package com.metropulse.operations.headway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how far apart, in time, consecutive vehicles on a route are running.
 *
 * <p>Vehicles are ordered by their normalised position along the route shape. For each vehicle the
 * one ahead of it is its leader, and the headway is how long the follower would take to cover the
 * gap between them:
 *
 * <pre>
 *   gap along the shape (meters) / a speed (m/s)
 * </pre>
 *
 * <p><strong>This is an estimate, and the method matters more than the number.</strong> It assumes
 * distance along the shape is distance travelled, and that the speed used holds for the whole gap.
 *
 * <h2>Which speed</h2>
 *
 * <p>Normally the follower's own speed is used. That breaks down exactly where it matters most: a
 * vehicle queued directly behind another is barely moving, and dividing by its speed would report a
 * huge headway - or nothing at all - for a pair that is plainly bunched. Two vehicles twelve meters
 * apart are bunched whatever the follower's speedometer says.
 *
 * <p>So when the follower is below a speed floor, the route's reference speed is used instead: the
 * median speed of the vehicles on that route that are actually moving. The pair is still measured in
 * time rather than distance, which keeps it comparable with the route's target headway, and the
 * basis is reported so the reading is never passed off as directly observed.
 *
 * <p>When nothing on the route is moving, no headway is produced. A stationary route has no service
 * interval to speak of.
 *
 * <h2>Shape of the route</h2>
 *
 * <p>The route is treated as a loop: the last vehicle's leader is the first, wrapping past the end of
 * the shape. That suits the development simulator, which runs the shape continuously. A scheduled
 * service that starts and ends a trip would want the open-ended form, where the vehicle in front has
 * no follower.
 */
public final class HeadwayCalculator {

    /**
     * Speeds at or below this are treated as "not moving" for headway purposes.
     *
     * <p>A MetroPulse project value: below a walking pace, a speed reading says more about GPS noise
     * than about the service.
     */
    public static final double MINIMUM_SPEED_KPH = 3.0;

    private HeadwayCalculator() {
    }

    /**
     * Calculates the headway of every vehicle to the one ahead of it.
     *
     * @param vehicles          vehicles on one route, in any order; fewer than two produces no pairs
     * @param routeLengthMeters length of the route shape
     * @return one entry per vehicle, ordered by position along the shape
     */
    public static List<HeadwayPair> calculate(List<VehiclePosition> vehicles, double routeLengthMeters) {
        if (vehicles == null || vehicles.size() < 2 || routeLengthMeters <= 0) {
            return List.of();
        }

        List<VehiclePosition> ordered = vehicles.stream()
                .sorted(Comparator.comparingDouble(VehiclePosition::routeProgress))
                .toList();

        Double referenceSpeedKph = referenceSpeedKph(ordered);

        List<HeadwayPair> pairs = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            VehiclePosition follower = ordered.get(index);
            VehiclePosition leader = ordered.get((index + 1) % ordered.size());

            double gapMeters = gapMeters(follower.routeProgress(), leader.routeProgress(), routeLengthMeters);
            pairs.add(pair(leader, follower, gapMeters, referenceSpeedKph));
        }

        return pairs;
    }

    private static HeadwayPair pair(
            VehiclePosition leader,
            VehiclePosition follower,
            double gapMeters,
            Double referenceSpeedKph
    ) {
        if (follower.speedKph() > MINIMUM_SPEED_KPH) {
            return new HeadwayPair(
                    leader.vehicleId(),
                    follower.vehicleId(),
                    gapMeters,
                    secondsToCover(gapMeters, follower.speedKph()),
                    HeadwayBasis.FOLLOWER_SPEED);
        }

        if (referenceSpeedKph != null) {
            return new HeadwayPair(
                    leader.vehicleId(),
                    follower.vehicleId(),
                    gapMeters,
                    secondsToCover(gapMeters, referenceSpeedKph),
                    HeadwayBasis.ROUTE_REFERENCE_SPEED);
        }

        return new HeadwayPair(leader.vehicleId(), follower.vehicleId(), gapMeters, null, HeadwayBasis.UNKNOWN);
    }

    /** Median speed of the vehicles that are moving, or null when none of them are. */
    private static Double referenceSpeedKph(List<VehiclePosition> vehicles) {
        List<Double> moving = vehicles.stream()
                .map(VehiclePosition::speedKph)
                .filter(speed -> speed > MINIMUM_SPEED_KPH)
                .sorted()
                .toList();

        if (moving.isEmpty()) {
            return null;
        }
        return moving.get(moving.size() / 2);
    }

    /** Distance from the follower forward to the leader, wrapping around the end of the shape. */
    private static double gapMeters(double followerProgress, double leaderProgress, double routeLengthMeters) {
        double gap = leaderProgress - followerProgress;
        if (gap <= 0) {
            gap += 1.0;
        }
        return gap * routeLengthMeters;
    }

    private static double secondsToCover(double gapMeters, double speedKph) {
        return gapMeters / (speedKph * 1000.0 / 3600.0);
    }

    /** A vehicle's position along a route shape, as the projection stores it. */
    public record VehiclePosition(String vehicleId, double routeProgress, double speedKph) {
    }
}
