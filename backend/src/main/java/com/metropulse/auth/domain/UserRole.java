package com.metropulse.auth.domain;

/**
 * What an operator is allowed to do.
 *
 * <p>Roles describe jobs, not permissions, so the set stays small and the authorisation rules read
 * like the organisation chart. A permission model would be more flexible and much harder to reason
 * about for a control room where everyone's job is well defined.
 */
public enum UserRole {

    /** Everything, including user and schedule administration. */
    ADMIN,

    /** Runs the network: acknowledges alerts, opens and works incidents, plugs vehicles in. */
    CONTROLLER,

    /** Fleet and depot focus: EV operations and vehicle state. */
    FLEET_SUPERVISOR,

    /** Schedule and analysis, no live intervention. */
    PLANNER,

    /** Read-only. */
    VIEWER;

    /** Spring Security expects authorities prefixed with {@code ROLE_}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
