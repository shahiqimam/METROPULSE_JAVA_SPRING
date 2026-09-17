package com.metropulse.auth.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Revokes refresh tokens in a transaction of its own.
 *
 * <p>This exists because of a specific failure. When a rotated token is presented again, the token
 * has leaked, and the response is to revoke every token the user holds and then reject the request.
 * Doing both in one transaction means the rejection's exception rolls back the revocation: the thief
 * is turned away once and the family stays valid, which is the opposite of what was intended.
 *
 * <p>Separate bean, and {@code REQUIRES_NEW}: separate because Spring's transaction proxy is bypassed
 * when a class calls its own annotated method, and REQUIRES_NEW so the revocation commits whatever
 * happens to the transaction that asked for it.
 */
@Component
public class RefreshTokenRevoker {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public RefreshTokenRevoker(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(long userId, String reason) {
        jdbcTemplate.update("""
                UPDATE refresh_token
                SET revoked_at = ?,
                    revoked_reason = ?
                WHERE user_id = ?
                  AND revoked_at IS NULL
                """,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                reason,
                userId);
    }
}
