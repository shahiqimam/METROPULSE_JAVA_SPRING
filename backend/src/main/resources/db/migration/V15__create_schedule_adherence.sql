-- Schedule adherence: comparing what a vehicle did against what it was supposed to do.

-- Which trip an observation belongs to.
--
-- Reported by the vehicle rather than inferred. A real AVL system knows the block and trip it was
-- dispatched on, and guessing it from position and time is a source of error that has nothing to do
-- with the measurement being made: two trips on the same route pass the same point.
ALTER TABLE vehicle_telemetry
    ADD COLUMN trip_id BIGINT REFERENCES trip(id);

CREATE INDEX idx_vehicle_telemetry_trip
    ON vehicle_telemetry (trip_id, recorded_at DESC)
    WHERE trip_id IS NOT NULL;

ALTER TABLE vehicle_current_state
    ADD COLUMN active_trip_id BIGINT REFERENCES trip(id),
    -- Positive is late, negative is early, null means not yet measurable.
    ADD COLUMN schedule_deviation_seconds INTEGER,
    ADD COLUMN next_stop_id BIGINT REFERENCES stop(id);

-- When a vehicle actually called at a stop.
--
-- One row per trip and stop: a vehicle calls at each stop of a trip once, and recording it twice
-- would mean either a detection bug or a vehicle that doubled back, both of which are worth a
-- constraint violation rather than a silent second row.
CREATE TABLE stop_arrival (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    stop_id BIGINT NOT NULL REFERENCES stop(id),
    vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    stop_sequence INTEGER NOT NULL,
    service_date DATE NOT NULL,
    arrived_at TIMESTAMPTZ NOT NULL,
    departed_at TIMESTAMPTZ,
    dwell_seconds INTEGER,
    planned_arrival_seconds INTEGER NOT NULL,
    actual_arrival_seconds INTEGER NOT NULL,
    -- actual minus planned. Positive is late, which is the convention everywhere in this system.
    deviation_seconds INTEGER NOT NULL,
    CONSTRAINT uq_stop_arrival_trip_stop UNIQUE (trip_id, stop_id, service_date)
);

CREATE INDEX idx_stop_arrival_service_date
    ON stop_arrival (service_date, arrived_at DESC);

CREATE INDEX idx_stop_arrival_vehicle
    ON stop_arrival (vehicle_id, arrived_at DESC);

CREATE INDEX idx_stop_arrival_deviation
    ON stop_arrival (deviation_seconds);
