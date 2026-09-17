package com.metropulse.operations.headway;

/** The two ways a pair's spacing can be wrong. */
public enum HeadwayConditionType {
    /** The follower has closed on its leader: the two are running together. */
    BUNCHING,
    /** The follower has fallen too far behind: passengers wait longer than the service promises. */
    EXCESSIVE_GAP
}
