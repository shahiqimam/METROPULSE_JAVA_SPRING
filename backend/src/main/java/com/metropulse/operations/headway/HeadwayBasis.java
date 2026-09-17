package com.metropulse.operations.headway;

/**
 * Which speed a headway was derived from.
 *
 * <p>Reported alongside the number so an estimate is never mistaken for a direct observation.
 */
public enum HeadwayBasis {
    /** The follower's own speed: the normal case. */
    FOLLOWER_SPEED,
    /** The route's median moving speed, used when the follower is stopped or crawling. */
    ROUTE_REFERENCE_SPEED,
    /** No headway could be derived, because nothing on the route is moving. */
    UNKNOWN
}
