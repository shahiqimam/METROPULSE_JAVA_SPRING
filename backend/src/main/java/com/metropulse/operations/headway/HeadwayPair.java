package com.metropulse.operations.headway;

/**
 * One follower and the vehicle ahead of it.
 *
 * @param headwaySeconds null only when nothing on the route is moving
 * @param basis          which speed the headway was derived from
 */
public record HeadwayPair(
        String leaderVehicleId,
        String followerVehicleId,
        double gapMeters,
        Double headwaySeconds,
        HeadwayBasis basis
) {

    /** A fingerprint identifies the same pair across evaluations, so a condition is not duplicated. */
    public String fingerprint(HeadwayConditionType type, String routeCode) {
        return String.join("|", type.name(), routeCode, leaderVehicleId, followerVehicleId);
    }
}
