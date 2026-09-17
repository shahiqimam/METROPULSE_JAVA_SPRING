package com.metropulse.alert.domain;

/** Why an alert stopped being live. */
public enum AlertCloseReason {
    /** The condition went away and stayed away for the recovery window. */
    RECOVERED,
    /** A controller closed it by hand. */
    CLOSED_BY_CONTROLLER
}
