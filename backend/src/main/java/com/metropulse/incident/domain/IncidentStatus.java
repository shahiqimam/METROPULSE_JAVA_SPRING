package com.metropulse.incident.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an incident is in its workflow.
 *
 * <p>The allowed transitions live here rather than being scattered across services, so there is one
 * answer to "can this incident go there" and it cannot drift between call sites. An incident that
 * has reached a terminal state stays there: reopening would lose the distinction between one long
 * incident and two related ones, and the timeline would stop being a reliable account of what
 * happened.
 */
public enum IncidentStatus {

    /** Reported, nobody has picked it up. */
    OPEN,

    /** A controller has taken it. */
    ACKNOWLEDGED,

    /** Something is being done about it. */
    MITIGATING,

    /** Over, and dealt with. */
    RESOLVED,

    /** Should not have been raised, or no longer applies. */
    CANCELLED;

    public boolean isTerminal() {
        return this == RESOLVED || this == CANCELLED;
    }

    /** The states this one may move to. */
    public Set<IncidentStatus> allowedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(ACKNOWLEDGED, MITIGATING, RESOLVED, CANCELLED);
            case ACKNOWLEDGED -> EnumSet.of(MITIGATING, RESOLVED, CANCELLED);
            // Mitigation cannot be cancelled: work has already been done, so it ends resolved.
            case MITIGATING -> EnumSet.of(RESOLVED);
            case RESOLVED, CANCELLED -> EnumSet.noneOf(IncidentStatus.class);
        };
    }

    public boolean canTransitionTo(IncidentStatus target) {
        return allowedTransitions().contains(target);
    }
}
