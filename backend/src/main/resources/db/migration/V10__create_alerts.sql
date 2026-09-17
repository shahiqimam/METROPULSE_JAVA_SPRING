-- Alerts: the operational conditions a controller is asked to look at.

CREATE TABLE alert (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(40) NOT NULL,
    fingerprint VARCHAR(200) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    vehicle_id BIGINT REFERENCES vehicle(id),
    route_id BIGINT REFERENCES route(id),
    opened_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    recovering_since TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by VARCHAR(120),
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(40),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT chk_alert_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'CLOSED')),
    CONSTRAINT chk_alert_severity CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR'))
);

-- The deduplication rule: one live alert per fingerprint.
--
-- A vehicle that has been off route for an hour is one alert that is still true, not one alert per
-- evaluation tick. Closed alerts are excluded from the constraint so the same condition can recur
-- later as a new alert with its own history.
CREATE UNIQUE INDEX uq_alert_live_fingerprint
    ON alert (fingerprint)
    WHERE status <> 'CLOSED';

CREATE INDEX idx_alert_status_opened_at
    ON alert (status, opened_at DESC);

CREATE INDEX idx_alert_vehicle
    ON alert (vehicle_id)
    WHERE status <> 'CLOSED';

-- Conditions that are true but have not yet lasted long enough to raise an alert.
--
-- Most rules require their condition to persist before it counts, so the moment a condition was
-- first seen has to be remembered somewhere. Keeping it out of the alert table means a candidate
-- that never matures leaves no trace in the controller's alert history.
CREATE TABLE alert_candidate (
    fingerprint VARCHAR(200) PRIMARY KEY,
    type VARCHAR(40) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_alert_candidate_first_observed
    ON alert_candidate (first_observed_at);
