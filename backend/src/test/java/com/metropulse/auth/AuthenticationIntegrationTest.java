package com.metropulse.auth;

import com.metropulse.auth.api.AuthTokens;
import com.metropulse.auth.application.AuthenticationService;
import com.metropulse.auth.application.TokenService;
import com.metropulse.auth.domain.InvalidCredentialsException;
import com.metropulse.auth.domain.InvalidRefreshTokenException;
import com.metropulse.auth.domain.UserRole;
import com.metropulse.support.MutableClock;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationIntegrationTest extends PostgisIntegrationTest {

    private static final String CONTROLLER_EMAIL = "controller@metropulse.test";
    private static final String CONTROLLER_PASSWORD = "controller-dev-password";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        clock.reset();
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("UPDATE app_user SET active = TRUE");
    }

    @Test
    void loggingInReturnsAnAccessTokenCarryingTheUsersRole() {
        AuthTokens tokens = login();

        Jwt jwt = jwtDecoder.decode(tokens.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(CONTROLLER_EMAIL);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CONTROLLER");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Dev Controller");
        assertThat(tokens.user().role()).isEqualTo(UserRole.CONTROLLER);
        assertThat(tokens.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void anExpiredAccessTokenIsRejected() {
        AuthTokens tokens = login();
        assertThat(jwtDecoder.decode(tokens.accessToken())).as("valid when minted").isNotNull();

        // Past the 15-minute lifetime and the validator's default 60-second clock skew.
        clock.advance(Duration.ofMinutes(17));

        assertThatThrownBy(() -> jwtDecoder.decode(tokens.accessToken()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void theRefreshTokenIsNeverStoredInTheClear() {
        AuthTokens tokens = login();

        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?", Integer.class, tokens.refreshToken());

        assertThat(matches).as("the raw token must not appear in the table").isZero();
        assertThat(refreshTokenCount()).isEqualTo(1);
    }

    @Test
    void aWrongPasswordIsRefused() {
        assertThatThrownBy(() -> authenticationService.login(CONTROLLER_EMAIL, "not-the-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void anUnknownAccountFailsTheSameWayAsAWrongPassword() {
        // Same exception and message, so the response cannot be used to enumerate accounts.
        assertThatThrownBy(() -> authenticationService.login("nobody@metropulse.test", "whatever"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password.");
        assertThatThrownBy(() -> authenticationService.login(CONTROLLER_EMAIL, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password.");
    }

    @Test
    void aDeactivatedAccountCannotLogIn() {
        jdbcTemplate.update("UPDATE app_user SET active = FALSE WHERE email = ?", CONTROLLER_EMAIL);

        assertThatThrownBy(() -> authenticationService.login(CONTROLLER_EMAIL, CONTROLLER_PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void emailMatchingIgnoresCase() {
        assertThat(authenticationService.login("CONTROLLER@METROPULSE.TEST", CONTROLLER_PASSWORD)).isNotNull();
    }

    @Test
    void refreshingReturnsANewPairAndRetiresTheOldToken() {
        AuthTokens first = login();

        AuthTokens second = authenticationService.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.user().email()).isEqualTo(CONTROLLER_EMAIL);
        assertThat(jwtDecoder.decode(second.accessToken()).getClaimAsString("role")).isEqualTo("CONTROLLER");
    }

    @Test
    void aRotatedTokenCannotBeUsedAgain() {
        AuthTokens first = login();
        authenticationService.refresh(first.refreshToken());

        assertThatThrownBy(() -> authenticationService.refresh(first.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void reusingARotatedTokenLogsTheSessionOutEverywhere() {
        AuthTokens first = login();
        AuthTokens second = authenticationService.refresh(first.refreshToken());

        // Reuse of the retired token means it leaked: the legitimate holder has the replacement.
        assertThatThrownBy(() -> authenticationService.refresh(first.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThatThrownBy(() -> authenticationService.refresh(second.refreshToken()))
                .as("the replacement is revoked too, so the thief gains nothing")
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void aLoggedOutTokenIsRejectedWithoutBurningTheUsersOtherSessions() {
        AuthTokens session = login();
        AuthTokens otherDevice = login();
        authenticationService.logout(session.refreshToken());

        // Presenting a logged-out token is not evidence of a leak, so it must not be treated as one.
        assertThatThrownBy(() -> authenticationService.refresh(session.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(authenticationService.refresh(otherDevice.refreshToken())).isNotNull();
    }

    @Test
    void anExpiredRefreshTokenIsRefused() {
        AuthTokens tokens = login();

        clock.advance(Duration.ofDays(8));

        assertThatThrownBy(() -> authenticationService.refresh(tokens.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void anUnknownRefreshTokenIsRefused() {
        assertThatThrownBy(() -> authenticationService.refresh("not-a-real-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void loggingOutRevokesOnlyThePresentedToken() {
        AuthTokens session = login();
        AuthTokens otherDevice = login();

        authenticationService.logout(session.refreshToken());

        assertThatThrownBy(() -> authenticationService.refresh(session.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(authenticationService.refresh(otherDevice.refreshToken()))
                .as("logging out on one device must not sign the operator out everywhere")
                .isNotNull();
    }

    @Test
    void aDeactivatedAccountCannotRefreshEitherWhileHoldingAValidToken() {
        AuthTokens tokens = login();
        jdbcTemplate.update("UPDATE app_user SET active = FALSE WHERE email = ?", CONTROLLER_EMAIL);

        assertThatThrownBy(() -> authenticationService.refresh(tokens.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokingEveryTokenForAUserEndsAllTheirSessions() {
        AuthTokens first = login();
        AuthTokens second = login();
        long userId = first.user().id();

        tokenService.revokeAllForUser(userId, "ADMIN_ACTION");

        assertThatThrownBy(() -> authenticationService.refresh(first.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> authenticationService.refresh(second.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void aTamperedAccessTokenIsRejected() {
        AuthTokens tokens = login();
        String tampered = tokens.accessToken().substring(0, tokens.accessToken().length() - 4) + "AAAA";

        assertThatThrownBy(() -> jwtDecoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void everySeededRoleCanLogIn() {
        assertThat(authenticationService.login("admin@metropulse.test", "admin-dev-password").user().role())
                .isEqualTo(UserRole.ADMIN);
        assertThat(authenticationService.login("supervisor@metropulse.test", "supervisor-dev-password").user().role())
                .isEqualTo(UserRole.FLEET_SUPERVISOR);
        assertThat(authenticationService.login("planner@metropulse.test", "planner-dev-password").user().role())
                .isEqualTo(UserRole.PLANNER);
        assertThat(authenticationService.login("viewer@metropulse.test", "viewer-dev-password").user().role())
                .isEqualTo(UserRole.VIEWER);
    }

    private AuthTokens login() {
        return authenticationService.login(CONTROLLER_EMAIL, CONTROLLER_PASSWORD);
    }

    private int refreshTokenCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class);
    }
}
