CREATE TABLE vehicle_current_state (
    vehicle_id BIGINT PRIMARY KEY REFERENCES vehicle(id),
    source_event_id VARCHAR(120) NOT NULL UNIQUE,
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    location geometry(Point, 4326) NOT NULL,
    speed_kph NUMERIC(6,2) NOT NULL,
    heading_degrees NUMERIC(6,2) NOT NULL,
    occupancy_estimate INTEGER NOT NULL,
    battery_percent INTEGER,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicle_current_state_recorded_at
    ON vehicle_current_state (recorded_at DESC);

CREATE INDEX idx_vehicle_current_state_location
    ON vehicle_current_state
    USING GIST (location);

INSERT INTO vehicle_current_state (
    vehicle_id,
    source_event_id,
    recorded_at,
    received_at,
    location,
    speed_kph,
    heading_degrees,
    occupancy_estimate,
    battery_percent
)
SELECT DISTINCT ON (vehicle_id)
    vehicle_id,
    source_event_id,
    recorded_at,
    received_at,
    location,
    speed_kph,
    heading_degrees,
    occupancy_estimate,
    battery_percent
FROM vehicle_telemetry
ORDER BY vehicle_id, recorded_at DESC, received_at DESC;
