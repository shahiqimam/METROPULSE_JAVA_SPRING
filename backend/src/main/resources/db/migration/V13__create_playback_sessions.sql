-- Playback: replaying what already happened.
--
-- A session records what was asked for, so the frames can be paged through by id rather than
-- re-sending the criteria on every request. Frames themselves are never stored: they are read from
-- vehicle_telemetry, which is immutable history. Copying them would create a second version of the
-- past that could drift from the first.

CREATE TABLE playback_session (
    id BIGSERIAL PRIMARY KEY,
    vehicle_id BIGINT REFERENCES vehicle(id),
    route_id BIGINT REFERENCES route(id),
    from_time TIMESTAMPTZ NOT NULL,
    to_time TIMESTAMPTZ NOT NULL,
    speed NUMERIC(4,1) NOT NULL DEFAULT 1.0,
    frame_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    CONSTRAINT chk_playback_window CHECK (to_time > from_time),
    CONSTRAINT chk_playback_speed CHECK (speed > 0 AND speed <= 60)
);

CREATE INDEX idx_playback_session_created_at
    ON playback_session (created_at DESC);
