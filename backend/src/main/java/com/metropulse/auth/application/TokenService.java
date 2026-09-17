package com.metropulse.auth.application;

import com.metropulse.auth.domain.AuthenticatedUser;
import com.metropulse.auth.domain.InvalidRefreshTokenException;
import com.metropulse.auth.domain.UserRole;
import com.metropulse.common.config.MetroPulseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/**
 * Issues and validates tokens.
 *
 * <h2>Two kinds of token, for two different reasons</h2>
 *
 * <p><strong>Access tokens</strong> are short-lived JWTs. They are self-contained, so every request
 * can be authorised without touching the database — which is the point — but that also means they
 * cannot be revoked. Their lifetime is the revocation window, which is why it is measured in minutes.
 *
 * <p><strong>Refresh tokens</strong> are long-lived opaque strings, stored hashed. They are checked
 * against the database on every use, so they can be revoked immediately. They are never accepted as
 * authorisation for anything except minting a new access token.
 *
 * <h2>Rotation</h2>
 *
 * <p>Every refresh mints a new refresh token and marks the old one used. A used token presented again
 * is not merely rejected: it means the token leaked, since the legitimate holder has moved on. The
 * whole family is revoked, logging the session out everywhere.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final MetroPulseProperties properties;
    private final Clock clock;

    public TokenService(
            JdbcTemplate jdbcTemplate,
            JwtEncoder jwtEncoder,
            RefreshTokenRevoker refreshTokenRevoker,
            MetroPulseProperties properties,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenRevoker = refreshTokenRevoker;
        this.properties = properties;
        this.clock = clock;
    }

    /** Mints a signed access token carrying the user's identity and role. */
    public String issueAccessToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        Duration lifetime = Duration.ofMinutes(properties.auth().accessTokenMinutes());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("metropulse")
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .subject(user.email())
                .claim("uid", user.id())
                .claim("name", user.displayName())
                // Single role, not a list: MetroPulse users have one job.
                .claim("role", user.role().name())
                .build();

        // The algorithm has to be stated: the encoder defaults to RS256 and would then fail to find
        // a signing key, since the configured key is an HMAC secret.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Issues a refresh token and stores only its hash.
     *
     * @return the token to hand to the client; it is never recoverable from the database afterwards
     */
    @Transactional
    public String issueRefreshToken(long userId) {
        String token = randomToken();
        Instant now = clock.instant();

        jdbcTemplate.update("""
                INSERT INTO refresh_token (user_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                userId,
                hash(token),
                at(now),
                at(now.plus(Duration.ofDays(properties.auth().refreshTokenDays()))));

        return token;
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * @return the user the token belonged to, with the old token now rotated out
     */
    @Transactional
    public AuthenticatedUser rotate(String refreshToken) {
        String tokenHash = hash(refreshToken);
        Instant now = clock.instant();

        List<StoredRefreshToken> tokens = jdbcTemplate.query("""
                SELECT
                    rt.id, rt.user_id, rt.expires_at, rt.revoked_at, rt.revoked_reason,
                    rt.replaced_by_hash,
                    u.email, u.display_name, u.role, u.active
                FROM refresh_token rt
                JOIN app_user u ON u.id = rt.user_id
                WHERE rt.token_hash = ?
                """,
                (rs, rowNum) -> new StoredRefreshToken(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("revoked_at", OffsetDateTime.class) != null,
                        "ROTATED".equals(rs.getString("revoked_reason")) || rs.getString("replaced_by_hash") != null,
                        new AuthenticatedUser(
                                rs.getLong("user_id"),
                                rs.getString("email"),
                                rs.getString("display_name"),
                                UserRole.valueOf(rs.getString("role"))),
                        rs.getBoolean("active")),
                tokenHash);

        if (tokens.isEmpty()) {
            throw new InvalidRefreshTokenException("unknown");
        }

        StoredRefreshToken stored = tokens.getFirst();

        if (stored.alreadyRotated()) {
            // A token that was rotated away has a legitimate successor, and its holder moved on to
            // that. Someone presenting the old one is presenting a copy, so the family is burned.
            log.warn("Refresh token reuse detected for user {}; revoking every token they hold.", stored.userId());
            // In its own transaction, so the exception thrown next cannot roll the revocation back.
            refreshTokenRevoker.revokeAllForUser(stored.userId(), "REUSE_DETECTED");
            throw new InvalidRefreshTokenException("already used");
        }

        if (stored.revoked()) {
            // Revoked for some other reason - a logout, or an administrator ending the session. That
            // is not evidence of a leak, and treating it as one would let a logged-out tab sign the
            // operator out of every other device they are using.
            throw new InvalidRefreshTokenException("revoked");
        }

        if (!stored.userActive()) {
            throw new InvalidRefreshTokenException("account deactivated");
        }

        if (stored.expiresAt().isBefore(now)) {
            throw new InvalidRefreshTokenException("expired");
        }

        jdbcTemplate.update("""
                UPDATE refresh_token
                SET revoked_at = ?,
                    revoked_reason = 'ROTATED'
                WHERE id = ?
                """,
                at(now),
                stored.id());

        return stored.user();
    }

    /** Marks a refresh token used and records what replaced it, so reuse can be recognised later. */
    @Transactional
    public void recordRotation(String oldToken, String newToken) {
        jdbcTemplate.update(
                "UPDATE refresh_token SET replaced_by_hash = ? WHERE token_hash = ?",
                hash(newToken),
                hash(oldToken));
    }

    /** Logout: revokes the presented token only, leaving the user's other sessions alone. */
    @Transactional
    public void revoke(String refreshToken) {
        jdbcTemplate.update("""
                UPDATE refresh_token
                SET revoked_at = ?,
                    revoked_reason = 'LOGOUT'
                WHERE token_hash = ?
                  AND revoked_at IS NULL
                """,
                at(clock.instant()),
                hash(refreshToken));
    }

    /** Ends every session a user has, for example when an administrator deactivates them. */
    public void revokeAllForUser(long userId, String reason) {
        refreshTokenRevoker.revokeAllForUser(userId, reason);
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not BCrypt.
     *
     * <p>BCrypt is deliberately slow to make guessing a low-entropy human password expensive. A
     * refresh token is 384 bits of randomness, so there is nothing to guess; what is needed is a fast
     * lookup by hash, which a salted slow hash cannot give.
     */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record StoredRefreshToken(
            long id,
            long userId,
            Instant expiresAt,
            boolean revoked,
            boolean alreadyRotated,
            AuthenticatedUser user,
            boolean userActive
    ) {
    }
}
