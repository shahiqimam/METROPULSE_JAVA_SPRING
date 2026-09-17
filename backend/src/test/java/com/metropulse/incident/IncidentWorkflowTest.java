package com.metropulse.incident;

import com.metropulse.incident.domain.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table on its own.
 *
 * <p>These assertions are the specification of the workflow: if a transition is legal, it is legal
 * here, and nowhere else gets to decide differently.
 */
class IncidentWorkflowTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN,         ACKNOWLEDGED, true",
            "OPEN,         MITIGATING,   true",
            "OPEN,         RESOLVED,     true",
            "OPEN,         CANCELLED,    true",
            "OPEN,         OPEN,         false",

            "ACKNOWLEDGED, MITIGATING,   true",
            "ACKNOWLEDGED, RESOLVED,     true",
            "ACKNOWLEDGED, CANCELLED,    true",
            "ACKNOWLEDGED, OPEN,         false",

            "MITIGATING,   RESOLVED,     true",
            "MITIGATING,   CANCELLED,    false",
            "MITIGATING,   ACKNOWLEDGED, false",
            "MITIGATING,   OPEN,         false",

            "RESOLVED,     OPEN,         false",
            "RESOLVED,     ACKNOWLEDGED, false",
            "RESOLVED,     MITIGATING,   false",
            "RESOLVED,     CANCELLED,    false",

            "CANCELLED,    OPEN,         false",
            "CANCELLED,    ACKNOWLEDGED, false",
            "CANCELLED,    MITIGATING,   false",
            "CANCELLED,    RESOLVED,     false"
    })
    void statesAllowExactlyTheTransitionsTheyShould(IncidentStatus from, IncidentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = IncidentStatus.class, names = {"RESOLVED", "CANCELLED"})
    void terminalStatesLeadNowhere(IncidentStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = IncidentStatus.class, names = {"OPEN", "ACKNOWLEDGED", "MITIGATING"})
    void liveStatesAreNotTerminalAndCanAlwaysReachResolved(IncidentStatus status) {
        assertThat(status.isTerminal()).isFalse();
        assertThat(status.canTransitionTo(IncidentStatus.RESOLVED))
                .as("every live incident must have a way to end")
                .isTrue();
    }

    @Test
    void mitigationCannotBeCancelled() {
        // Work has already been done, so the incident ends resolved rather than being written off.
        assertThat(IncidentStatus.MITIGATING.canTransitionTo(IncidentStatus.CANCELLED)).isFalse();
    }

    @Test
    void noStateCanReturnToOpen() {
        for (IncidentStatus status : IncidentStatus.values()) {
            assertThat(status.canTransitionTo(IncidentStatus.OPEN))
                    .as("%s should not be able to reopen", status)
                    .isFalse();
        }
    }
}
