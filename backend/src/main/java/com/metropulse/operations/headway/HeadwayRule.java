package com.metropulse.operations.headway;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides whether a pair's spacing counts as bunched or gapped.
 *
 * <p>The thresholds are ratios of the route's target headway, so a route operated every 5 minutes and
 * one operated every 30 seconds are judged on the same terms.
 *
 * <pre>
 *   BUNCHING       headway below 40% of target
 *   EXCESSIVE_GAP  headway above 180% of target
 * </pre>
 *
 * <p>Neither counts until it has held for the persistence window. Spacing fluctuates constantly - a
 * traffic light is enough to move it - and a rule without a persistence window would open and close
 * conditions continuously, which is how alerting becomes noise nobody reads.
 *
 * <p><strong>These are MetroPulse project thresholds, not transit-industry standards.</strong>
 */
public final class HeadwayRule {

    public static final double BUNCHING_RATIO = 0.40;
    public static final double EXCESSIVE_GAP_RATIO = 1.80;
    public static final Duration PERSISTENCE_WINDOW = Duration.ofSeconds(90);

    private HeadwayRule() {
    }

    /**
     * Classifies one pair against its route's target headway.
     *
     * @return the condition the pair is in, or empty when its spacing is acceptable or unknown
     */
    public static Optional<HeadwayConditionType> classify(Double headwaySeconds, int targetHeadwaySeconds) {
        if (headwaySeconds == null || targetHeadwaySeconds <= 0) {
            return Optional.empty();
        }

        double ratio = headwaySeconds / targetHeadwaySeconds;
        if (ratio < BUNCHING_RATIO) {
            return Optional.of(HeadwayConditionType.BUNCHING);
        }
        if (ratio > EXCESSIVE_GAP_RATIO) {
            return Optional.of(HeadwayConditionType.EXCESSIVE_GAP);
        }
        return Optional.empty();
    }

    /** True once a condition has held long enough to be treated as real rather than a fluctuation. */
    public static boolean hasPersisted(Duration observedFor) {
        return observedFor.compareTo(PERSISTENCE_WINDOW) >= 0;
    }
}
