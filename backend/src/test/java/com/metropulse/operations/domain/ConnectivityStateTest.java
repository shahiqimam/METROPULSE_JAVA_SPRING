package com.metropulse.operations.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectivityStateTest {

    @ParameterizedTest
    @CsvSource({
            "0.0, ONLINE",
            "14.999, ONLINE",
            "15.0, ONLINE",
            "15.001, STALE",
            "59.999, STALE",
            "60.0, STALE",
            "60.001, OFFLINE",
            "3600.0, OFFLINE"
    })
    void classifiesTelemetryAgeAtThresholdBoundaries(double ageSeconds, ConnectivityState expected) {
        assertThat(ConnectivityState.classify(ageSeconds)).isEqualTo(expected);
    }

    @Test
    void treatsSlightlyFutureTimestampsAsOnlineRatherThanOffline() {
        assertThat(ConnectivityState.classify(-2.0)).isEqualTo(ConnectivityState.ONLINE);
    }
}
