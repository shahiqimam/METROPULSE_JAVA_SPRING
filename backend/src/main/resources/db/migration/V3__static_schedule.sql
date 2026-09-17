CREATE TABLE agency (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE,
    timezone VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE route (
    id BIGSERIAL PRIMARY KEY,
    agency_id BIGINT NOT NULL REFERENCES agency(id),
    code VARCHAR(40) NOT NULL UNIQUE,
    short_name VARCHAR(80) NOT NULL,
    long_name VARCHAR(180) NOT NULL,
    geometry geometry(LineString, 4326) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_agency
    ON route (agency_id);

CREATE INDEX idx_route_geometry
    ON route
    USING GIST (geometry);

CREATE TABLE stop (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL,
    location geometry(Point, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stop_location
    ON stop
    USING GIST (location);

CREATE TABLE service_calendar (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    monday BOOLEAN NOT NULL,
    tuesday BOOLEAN NOT NULL,
    wednesday BOOLEAN NOT NULL,
    thursday BOOLEAN NOT NULL,
    friday BOOLEAN NOT NULL,
    saturday BOOLEAN NOT NULL,
    sunday BOOLEAN NOT NULL,
    CONSTRAINT chk_service_calendar_date_range CHECK (end_date >= start_date)
);

CREATE TABLE trip (
    id BIGSERIAL PRIMARY KEY,
    route_id BIGINT NOT NULL REFERENCES route(id),
    service_calendar_id BIGINT NOT NULL REFERENCES service_calendar(id),
    trip_code VARCHAR(80) NOT NULL UNIQUE,
    direction VARCHAR(40) NOT NULL,
    headsign VARCHAR(160) NOT NULL,
    planned_start_seconds INTEGER NOT NULL,
    planned_end_seconds INTEGER NOT NULL,
    CONSTRAINT chk_trip_planned_seconds CHECK (
        planned_start_seconds >= 0
        AND planned_end_seconds >= planned_start_seconds
        AND planned_end_seconds <= 172800
    )
);

CREATE INDEX idx_trip_route
    ON trip (route_id);

CREATE INDEX idx_trip_service_calendar
    ON trip (service_calendar_id);

CREATE TABLE stop_time (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    stop_id BIGINT NOT NULL REFERENCES stop(id),
    stop_sequence INTEGER NOT NULL,
    planned_arrival_seconds INTEGER NOT NULL,
    planned_departure_seconds INTEGER NOT NULL,
    CONSTRAINT uq_stop_time_trip_sequence UNIQUE (trip_id, stop_sequence),
    CONSTRAINT chk_stop_time_sequence CHECK (stop_sequence > 0),
    CONSTRAINT chk_stop_time_seconds CHECK (
        planned_arrival_seconds >= 0
        AND planned_departure_seconds >= planned_arrival_seconds
        AND planned_departure_seconds <= 172800
    )
);

CREATE INDEX idx_stop_time_trip_sequence
    ON stop_time (trip_id, stop_sequence);

CREATE INDEX idx_stop_time_stop
    ON stop_time (stop_id);
