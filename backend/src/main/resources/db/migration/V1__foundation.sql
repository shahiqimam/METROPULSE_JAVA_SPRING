CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE vehicle (
    id BIGSERIAL PRIMARY KEY,
    fleet_number VARCHAR(50) NOT NULL UNIQUE,
    vehicle_type VARCHAR(50) NOT NULL,
    propulsion_type VARCHAR(50) NOT NULL,
    capacity INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL
);

CREATE TABLE vehicle_telemetry (
    id BIGSERIAL PRIMARY KEY,
    source_event_id VARCHAR(120) NOT NULL UNIQUE,
    vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    location geometry(Point, 4326) NOT NULL,
    speed_kph NUMERIC(6,2) NOT NULL,
    heading_degrees NUMERIC(6,2) NOT NULL,
    occupancy_estimate INTEGER NOT NULL,
    battery_percent INTEGER,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_vehicle_telemetry_vehicle_recorded_at
    ON vehicle_telemetry (vehicle_id, recorded_at DESC);

CREATE INDEX idx_vehicle_telemetry_location
    ON vehicle_telemetry
    USING GIST (location);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_event_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
