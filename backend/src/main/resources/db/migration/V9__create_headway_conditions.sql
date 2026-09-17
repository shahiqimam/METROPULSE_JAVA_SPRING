-- Headway: how far apart in time consecutive vehicles on a route are running.

-- The spacing the route is meant to be operated at. Everything the headway rules say is relative to
-- this, so it belongs with the route rather than in application configuration.
ALTER TABLE route
    ADD COLUMN target_headway_seconds INTEGER NOT NULL DEFAULT 600;

ALTER TABLE route
    ADD CONSTRAINT chk_route_target_headway CHECK (target_headway_seconds > 0);

-- The seeded M42 shape is about 1.6 km and the development fleet of four runs it at roughly 28 kph,
-- so evenly spaced vehicles sit about 55 seconds apart. Targeting that makes the demo fleet nominal
-- until a scenario disturbs it.
UPDATE route SET target_headway_seconds = 55 WHERE code = 'M42';

-- A headway condition is one leader/follower pair whose spacing is currently outside target.
--
-- It is kept as a row rather than recomputed per request because the rules are time-based: a pair
-- has to stay out of range for a persistence window before it counts, which means remembering when
-- the condition was first seen. This table is also what the alert engine will be built on.
CREATE TABLE headway_condition (
    fingerprint VARCHAR(200) PRIMARY KEY,
    route_id BIGINT NOT NULL REFERENCES route(id),
    condition_type VARCHAR(40) NOT NULL,
    leader_vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    follower_vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    headway_seconds NUMERIC(10,2) NOT NULL,
    target_headway_seconds INTEGER NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT chk_headway_condition_type CHECK (condition_type IN ('BUNCHING', 'EXCESSIVE_GAP'))
);

CREATE INDEX idx_headway_condition_route
    ON headway_condition (route_id, condition_type);

CREATE INDEX idx_headway_condition_confirmed
    ON headway_condition (confirmed_at)
    WHERE confirmed_at IS NOT NULL;
