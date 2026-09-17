package com.metropulse.auth.api;

import com.metropulse.auth.domain.AuthenticatedUser;

/**
 * What a successful login or refresh returns.
 *
 * @param expiresInSeconds lifetime of the access token, so a client can refresh before it expires
 *                         rather than after a request has already failed
 */
public record AuthTokens(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        AuthenticatedUser user
) {
}
