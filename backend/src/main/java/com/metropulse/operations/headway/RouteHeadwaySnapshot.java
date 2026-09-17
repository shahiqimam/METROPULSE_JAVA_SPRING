package com.metropulse.operations.headway;

import java.util.List;

/**
 * What the spacing on one route looks like right now.
 *
 * @param vehiclesConsidered how many vehicles were fresh enough to place; pairs are only meaningful
 *                           relative to this, because stale vehicles are left out
 */
public record RouteHeadwaySnapshot(
        String routeCode,
        int targetHeadwaySeconds,
        double routeLengthMeters,
        int vehiclesConsidered,
        List<HeadwayPairView> pairs,
        List<HeadwayCondition> conditions
) {

    /**
     * One pair as the control centre reads it.
     *
     * @param basis which speed the headway was derived from, so an estimate reads as an estimate
     */
    public record HeadwayPairView(
            String leaderVehicleId,
            String followerVehicleId,
            double gapMeters,
            Double headwaySeconds,
            Double ratioToTarget,
            String classification,
            HeadwayBasis basis
    ) {
    }
}
