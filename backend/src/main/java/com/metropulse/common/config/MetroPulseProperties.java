package com.metropulse.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "metropulse")
public record MetroPulseProperties(
        Telemetry telemetry,
        Outbox outbox,
        Auth auth
) {
    public record Telemetry(String ingestKey) {
    }

    /**
     * Authentication settings.
     *
     * @param jwtSecret         HMAC signing secret; must be at least 32 bytes
     * @param accessTokenMinutes how long an access token lives. It cannot be revoked, so this is also
     *                           the revocation window.
     * @param refreshTokenDays  how long a refresh token lives before the operator must log in again
     * @param allowedOrigins    CORS allowlist; never a wildcard, because the API takes credentials
     */
    public record Auth(
            String jwtSecret,
            int accessTokenMinutes,
            int refreshTokenDays,
            List<String> allowedOrigins
    ) {
    }

    public record Outbox(
            boolean enabled,
            String topic,
            int batchSize,
            long publishDelayMs
    ) {
    }
}
