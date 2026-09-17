package com.metropulse.playback.domain;

/** Thrown when a playback session id does not exist. */
public class UnknownPlaybackSessionException extends RuntimeException {

    public UnknownPlaybackSessionException(long sessionId) {
        super("Unknown playback session: " + sessionId);
    }
}
