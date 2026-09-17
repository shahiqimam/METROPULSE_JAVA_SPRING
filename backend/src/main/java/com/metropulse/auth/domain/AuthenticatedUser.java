package com.metropulse.auth.domain;

/** Who is making a request. */
public record AuthenticatedUser(long id, String email, String displayName, UserRole role) {
}
