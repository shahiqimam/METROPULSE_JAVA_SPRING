package com.metropulse.auth.api;

import com.metropulse.auth.application.AuthenticationService;
import com.metropulse.auth.domain.AuthenticatedUser;
import com.metropulse.auth.domain.UserRole;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public AuthTokens login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public AuthTokens refresh(@Valid @RequestBody RefreshRequest request) {
        return authenticationService.refresh(request.refreshToken());
    }

    /**
     * Logout revokes the refresh token.
     *
     * <p>The access token cannot be revoked — that is the trade a self-contained token makes — so it
     * stays valid until it expires. Keeping access-token lifetime short is what bounds that window.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) RefreshRequest request) {
        authenticationService.logout(request == null ? null : request.refreshToken());
    }

    /** Who the presented access token says you are, read from the token rather than the database. */
    @GetMapping("/me")
    public AuthenticatedUser me(@AuthenticationPrincipal Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getClaim("uid") == null ? 0L : ((Number) jwt.getClaim("uid")).longValue(),
                jwt.getSubject(),
                jwt.getClaimAsString("name"),
                UserRole.valueOf(jwt.getClaimAsString("role")));
    }
}
