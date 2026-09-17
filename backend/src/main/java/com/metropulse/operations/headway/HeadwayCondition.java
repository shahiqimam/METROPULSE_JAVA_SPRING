package com.metropulse.operations.headway;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * A leader/follower pair whose spacing is currently outside target.
 *
 * @param confirmed true once the condition has held for the rule's persistence window; until then it
 *                  is being watched, not asserted
 */
public record HeadwayCondition(
        String fingerprint,
        String routeCode,
        HeadwayConditionType type,
        String leaderVehicleId,
        String followerVehicleId,
        Double headwaySeconds,
        int targetHeadwaySeconds,
        Instant firstObservedAt,
        @JsonIgnore Duration observedFor,
        boolean confirmed
) {

    /**
     * How the spacing compares with what the route is meant to run at.
     *
     * <p>Annotated because a record only serialises its components by default, and a derived value
     * that the client needs has to say so explicitly.
     */
    @JsonProperty("ratioToTarget")
    public double ratioToTarget() {
        if (headwaySeconds == null || targetHeadwaySeconds <= 0) {
            return 0.0;
        }
        return headwaySeconds / targetHeadwaySeconds;
    }

    /** The spell's length in seconds, which travels better over JSON than a Duration. */
    @JsonProperty("observedForSeconds")
    public long observedForSeconds() {
        return observedFor.toSeconds();
    }
}
