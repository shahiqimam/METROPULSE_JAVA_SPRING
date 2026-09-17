package com.metropulse.simulator.route;

import java.util.List;

/**
 * The polyline a simulated vehicle drives along.
 *
 * <p>The shape must match the route geometry seeded in the backend, because the backend projects
 * every observation onto that geometry: a simulator path that wanders off the seeded shape would
 * report route deviation that is an artefact of the simulator rather than a scenario.
 *
 * <p>Distances use a local equirectangular approximation (degrees scaled to meters around the
 * current latitude) rather than a full geodesic solution. Over a few kilometres of city street the
 * error is well under a meter, which is far below the thresholds MetroPulse reasons about. The
 * backend still measures deviation properly with PostGIS geography; this approximation only decides
 * where the synthetic vehicle claims to be.
 */
public final class RoutePath {

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;

    private final List<GeoPoint> points;
    private final double[] cumulativeMeters;

    public RoutePath(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("A route path needs at least two points.");
        }
        this.points = List.copyOf(points);
        this.cumulativeMeters = new double[this.points.size()];
        for (int i = 1; i < this.points.size(); i++) {
            cumulativeMeters[i] = cumulativeMeters[i - 1] + distanceMeters(this.points.get(i - 1), this.points.get(i));
        }
        if (lengthMeters() <= 0.0) {
            throw new IllegalArgumentException("A route path needs a positive length.");
        }
    }

    /** Total length of the shape in meters. */
    public double lengthMeters() {
        return cumulativeMeters[cumulativeMeters.length - 1];
    }

    /**
     * Position at a normalised offset along the shape.
     *
     * @param progress 0.0 at the first point, 1.0 at the last; values outside that range are clamped
     */
    public GeoPoint pointAt(double progress) {
        double target = clampProgress(progress) * lengthMeters();
        int segment = segmentFor(target);
        GeoPoint from = points.get(segment);
        GeoPoint to = points.get(segment + 1);
        double segmentLength = cumulativeMeters[segment + 1] - cumulativeMeters[segment];
        double ratio = segmentLength == 0.0 ? 0.0 : (target - cumulativeMeters[segment]) / segmentLength;

        return new GeoPoint(
                from.latitude() + (to.latitude() - from.latitude()) * ratio,
                from.longitude() + (to.longitude() - from.longitude()) * ratio);
    }

    /** Compass bearing in degrees (0 = north, 90 = east) of the segment containing {@code progress}. */
    public double headingAt(double progress) {
        int segment = segmentFor(clampProgress(progress) * lengthMeters());
        GeoPoint from = points.get(segment);
        GeoPoint to = points.get(segment + 1);

        double eastMeters = (to.longitude() - from.longitude()) * metersPerDegreeLongitude(from.latitude());
        double northMeters = (to.latitude() - from.latitude()) * METERS_PER_DEGREE_LATITUDE;
        double bearing = Math.toDegrees(Math.atan2(eastMeters, northMeters));

        return (bearing + 360.0) % 360.0;
    }

    /**
     * Moves a point sideways, perpendicular to the direction of travel.
     *
     * <p>Used to place a vehicle off its route shape on purpose, so route deviation is a scenario
     * input with a known magnitude instead of an accident of the synthetic path.
     *
     * @param offsetMeters positive values move to the right of travel, negative to the left
     */
    public GeoPoint offsetFromPath(GeoPoint point, double headingDegrees, double offsetMeters) {
        if (offsetMeters == 0.0) {
            return point;
        }

        double perpendicular = Math.toRadians((headingDegrees + 90.0) % 360.0);
        double northMeters = Math.cos(perpendicular) * offsetMeters;
        double eastMeters = Math.sin(perpendicular) * offsetMeters;

        return new GeoPoint(
                point.latitude() + northMeters / METERS_PER_DEGREE_LATITUDE,
                point.longitude() + eastMeters / metersPerDegreeLongitude(point.latitude()));
    }

    /** Straight-line distance between two positions in meters. */
    public static double distanceMeters(GeoPoint from, GeoPoint to) {
        double averageLatitude = (from.latitude() + to.latitude()) / 2.0;
        double northMeters = (to.latitude() - from.latitude()) * METERS_PER_DEGREE_LATITUDE;
        double eastMeters = (to.longitude() - from.longitude()) * metersPerDegreeLongitude(averageLatitude);

        return Math.hypot(northMeters, eastMeters);
    }

    private static double metersPerDegreeLongitude(double latitude) {
        return METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude));
    }

    private int segmentFor(double distanceMeters) {
        for (int i = 1; i < cumulativeMeters.length; i++) {
            if (distanceMeters <= cumulativeMeters[i]) {
                return i - 1;
            }
        }
        return cumulativeMeters.length - 2;
    }

    private static double clampProgress(double progress) {
        return Math.max(0.0, Math.min(1.0, progress));
    }
}
