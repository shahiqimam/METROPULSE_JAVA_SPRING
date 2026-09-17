package com.metropulse.auth.domain;

/** Thrown when a refresh token is unknown, expired, revoked, or already used. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String reason) {
        super("Refresh token rejected: " + reason);
    }
}
