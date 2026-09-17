package com.metropulse.operations.headway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HeadwayRuleTest {

    private static final int TARGET = 600;

    @ParameterizedTest
    @CsvSource({
            // headway, expected classification against a 600s target
            "0,     BUNCHING",       // on top of the leader
            "239,   BUNCHING",       // just under 40%
            "240,   NONE",           // exactly 40% is not bunched
            "241,   NONE",
            "600,   NONE",           // on target
            "1080,  NONE",           // exactly 180% is not a gap
            "1079,  NONE",
            "1081,  EXCESSIVE_GAP",  // just over 180%
            "3600,  EXCESSIVE_GAP"
    })
    void classifiesSpacingAtTheThresholdBoundaries(double headwaySeconds, String expected) {
        Optional<HeadwayConditionType> classification = HeadwayRule.classify(headwaySeconds, TARGET);

        if ("NONE".equals(expected)) {
            assertThat(classification).isEmpty();
        } else {
            assertThat(classification).contains(HeadwayConditionType.valueOf(expected));
        }
    }

    @Test
    void anUnknownHeadwayIsNotClassified() {
        assertThat(HeadwayRule.classify(null, TARGET)).isEmpty();
    }

    @Test
    void aRouteWithoutATargetCannotBeJudged() {
        assertThat(HeadwayRule.classify(10.0, 0)).isEmpty();
    }

    @Test
    void theSameRatioClassifiesTheSameWayOnFastAndSlowRoutes() {
        // 30% of target on a 30-second headway route and on a 20-minute one.
        assertThat(HeadwayRule.classify(9.0, 30)).contains(HeadwayConditionType.BUNCHING);
        assertThat(HeadwayRule.classify(360.0, 1200)).contains(HeadwayConditionType.BUNCHING);
    }

    @ParameterizedTest
    @CsvSource({
            "0,   false",
            "89,  false",
            "90,  true",
            "91,  true",
            "600, true"
    })
    void aConditionCountsOnlyAfterItHasHeldForThePersistenceWindow(long seconds, boolean expected) {
        assertThat(HeadwayRule.hasPersisted(Duration.ofSeconds(seconds))).isEqualTo(expected);
    }
}
