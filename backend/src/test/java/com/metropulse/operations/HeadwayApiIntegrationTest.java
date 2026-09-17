package com.metropulse.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayConditionType;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a headway condition looks like on the wire.
 *
 * <p>A record only serialises its components, so values the client needs but the record derives -
 * the ratio to target, the length of the spell - are easy to lose without noticing. These assertions
 * exist because that happened.
 */
class HeadwayApiIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aConditionSerialisesItsDerivedValues() throws Exception {
        HeadwayCondition condition = new HeadwayCondition(
                "BUNCHING|M42|BUS-042|BUS-101",
                "M42",
                HeadwayConditionType.BUNCHING,
                "BUS-042",
                "BUS-101",
                11.0,
                55,
                Instant.parse("2026-09-17T09:00:00Z"),
                Duration.ofSeconds(120),
                true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(condition));

        assertThat(json.get("type").asText()).isEqualTo("BUNCHING");
        assertThat(json.get("leaderVehicleId").asText()).isEqualTo("BUS-042");
        assertThat(json.get("followerVehicleId").asText()).isEqualTo("BUS-101");
        assertThat(json.get("headwaySeconds").asDouble()).isEqualTo(11.0);
        assertThat(json.get("targetHeadwaySeconds").asInt()).isEqualTo(55);
        assertThat(json.get("confirmed").asBoolean()).isTrue();

        assertThat(json.get("ratioToTarget").asDouble()).isEqualTo(0.2);
        assertThat(json.get("observedForSeconds").asLong()).isEqualTo(120);
        assertThat(json.has("observedFor")).as("the Duration itself is not part of the contract").isFalse();
    }

    @Test
    void aConditionWithAnUnknownHeadwayReportsZeroRatioRatherThanFailing() {
        HeadwayCondition condition = new HeadwayCondition(
                "BUNCHING|M42|BUS-042|BUS-101",
                "M42",
                HeadwayConditionType.BUNCHING,
                "BUS-042",
                "BUS-101",
                null,
                55,
                Instant.parse("2026-09-17T09:00:00Z"),
                Duration.ZERO,
                false);

        assertThat(condition.ratioToTarget()).isZero();
    }
}
