-- Authentication: operator accounts and refresh tokens.

-- app_user exists from V1 with email, password_hash, role and active.
ALTER TABLE app_user
    ADD COLUMN display_name VARCHAR(160);

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role CHECK (
        role IN ('ADMIN', 'CONTROLLER', 'FLEET_SUPERVISOR', 'PLANNER', 'VIEWER')
    );

-- Refresh tokens are stored hashed, never in the clear.
--
-- A refresh token is a long-lived credential: anyone holding one can mint access tokens until it
-- expires. Storing it in the clear means a database read is a complete account takeover, so only a
-- SHA-256 of the token is kept - enough to recognise a token that is presented, useless to someone
-- who steals the table.
--
-- Rows are kept after use rather than deleted, because "this token was already used" and "this token
-- never existed" need different answers: reuse of a rotated token is a sign the token leaked.
CREATE TABLE refresh_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(40),
    replaced_by_hash VARCHAR(128),
    CONSTRAINT chk_refresh_token_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX idx_refresh_token_user
    ON refresh_token (user_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_refresh_token_expires_at
    ON refresh_token (expires_at);

-- Development operators, one per role.
--
-- These are fixed, well-known credentials for a synthetic demo. They are documented as such in the
-- README, and the production-style compose file is expected to replace them. A real deployment would
-- not ship accounts in a migration at all.
INSERT INTO app_user (email, password_hash, role, active, display_name)
VALUES
    ('admin@metropulse.test',      '$2a$10$wzuQL3gbqID/fBU8CjDzEO9JwPJQl0dglBV5SRxevd6TUIFPO27PS', 'ADMIN',            TRUE, 'Dev Admin'),
    ('controller@metropulse.test', '$2a$10$/6qX2ikJf03RSGOZYn5LQO2wVeZm3May4XEIfpA0Yqvk15p6UAAeC', 'CONTROLLER',       TRUE, 'Dev Controller'),
    ('supervisor@metropulse.test', '$2a$10$W9Do6ZpWhiVuipeATc9cmOqAjYvW4bPNnbpOH9kt92wavdz7YRwWS', 'FLEET_SUPERVISOR', TRUE, 'Dev Fleet Supervisor'),
    ('planner@metropulse.test',    '$2a$10$wq7tCE1IxutL1IQLmxou8uebThR3rIT4US5bnvwSV2DV.WNLg26TS', 'PLANNER',          TRUE, 'Dev Planner'),
    ('viewer@metropulse.test',     '$2a$10$SRovPVobJ6JgoGHYmKh62Og1kcY/QlbkzT1/ygbJAlcdB1mkXlA22', 'VIEWER',           TRUE, 'Dev Viewer')
ON CONFLICT (email) DO NOTHING;
