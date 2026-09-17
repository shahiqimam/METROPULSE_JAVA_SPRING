package com.metropulse.auth.application;

import com.metropulse.auth.api.AuthTokens;
import com.metropulse.auth.domain.AuthenticatedUser;
import com.metropulse.auth.domain.InvalidCredentialsException;
import com.metropulse.auth.domain.UserRole;
import com.metropulse.common.config.MetroPulseProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Login, refresh and logout.
 *
 * <p>Every failure path throws the same exception with the same message. Telling a caller that an
 * email exists but the password was wrong hands them half the credential.
 */
@Service
public class AuthenticationService {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final MetroPulseProperties properties;

    public AuthenticationService(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            MetroPulseProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.properties = properties;
    }

    @Transactional
    public AuthTokens login(String email, String password) {
        List<StoredUser> users = jdbcTemplate.query("""
                SELECT id, email, password_hash, display_name, role, active
                FROM app_user
                WHERE lower(email) = lower(?)
                """,
                (rs, rowNum) -> new StoredUser(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("active")),
                email);

        if (users.isEmpty()) {
            // Hash anyway so a missing account does not answer faster than a wrong password.
            passwordEncoder.matches(password, "$2a$10$ignoredignoredignoredignoredignoredignoredignoredignoredig");
            throw new InvalidCredentialsException();
        }

        StoredUser user = users.getFirst();
        if (!user.active() || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        AuthenticatedUser authenticated =
                new AuthenticatedUser(user.id(), user.email(), user.displayName(), user.role());

        return issueTokens(authenticated);
    }

    @Transactional
    public AuthTokens refresh(String refreshToken) {
        AuthenticatedUser user = tokenService.rotate(refreshToken);
        AuthTokens tokens = issueTokens(user);
        tokenService.recordRotation(refreshToken, tokens.refreshToken());
        return tokens;
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenService.revoke(refreshToken);
        }
    }

    private AuthTokens issueTokens(AuthenticatedUser user) {
        return new AuthTokens(
                tokenService.issueAccessToken(user),
                tokenService.issueRefreshToken(user.id()),
                properties.auth().accessTokenMinutes() * 60L,
                user);
    }

    private record StoredUser(
            long id,
            String email,
            String passwordHash,
            String displayName,
            UserRole role,
            boolean active
    ) {
    }
}
