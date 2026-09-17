-- EV operations: where electric vehicles charge, and who has which charger.

CREATE TABLE depot (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    location geometry(Point, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_depot_location
    ON depot
    USING GIST (location);

CREATE TABLE charger (
    id BIGSERIAL PRIMARY KEY,
    depot_id BIGINT NOT NULL REFERENCES depot(id),
    code VARCHAR(40) NOT NULL UNIQUE,
    power_kw NUMERIC(6,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- Optimistic lock column. The reservation path takes a pessimistic lock instead, because two
    -- controllers claiming the same charger is an expected race rather than an unlikely one; this
    -- column guards the ordinary edits (taking a charger out of service, changing its rating).
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_charger_status CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'OFFLINE', 'MAINTENANCE')),
    CONSTRAINT chk_charger_power CHECK (power_kw > 0)
);

CREATE INDEX idx_charger_depot_status
    ON charger (depot_id, status);

CREATE TABLE charging_session (
    id BIGSERIAL PRIMARY KEY,
    vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    charger_id BIGINT NOT NULL REFERENCES charger(id),
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    start_battery_percent INTEGER,
    end_battery_percent INTEGER,
    started_by VARCHAR(120) NOT NULL,
    CONSTRAINT chk_charging_session_status CHECK (
        status IN ('ACTIVE', 'COMPLETED', 'INTERRUPTED')
    ),
    CONSTRAINT chk_charging_session_battery CHECK (
        (start_battery_percent IS NULL OR start_battery_percent BETWEEN 0 AND 100)
        AND (end_battery_percent IS NULL OR end_battery_percent BETWEEN 0 AND 100)
    )
);

-- A charger can hold at most one active session, and a vehicle can be in at most one.
--
-- These are the database's own statement of the rule. The service takes a lock to give a clean 409
-- rather than a constraint violation, but the constraints are what make the rule true even if a
-- future code path forgets to lock.
CREATE UNIQUE INDEX uq_charging_session_active_charger
    ON charging_session (charger_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_charging_session_active_vehicle
    ON charging_session (vehicle_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_charging_session_started_at
    ON charging_session (started_at DESC);

-- Development seed: two depots at the ends of the M42 shape, with chargers.
INSERT INTO depot (code, name, location)
VALUES
    ('DEP-WEST', 'West Terminal Depot', ST_SetSRID(ST_MakePoint(-74.0065, 40.7125), 4326)),
    ('DEP-EAST', 'East Terminal Depot', ST_SetSRID(ST_MakePoint(-73.9895, 40.7181), 4326))
ON CONFLICT (code) DO NOTHING;

INSERT INTO charger (depot_id, code, power_kw, status)
SELECT depot.id, seed.code, seed.power_kw, seed.status
FROM depot
JOIN (
    VALUES
        ('DEP-WEST', 'CHG-W01', 150.0, 'AVAILABLE'),
        ('DEP-WEST', 'CHG-W02', 150.0, 'AVAILABLE'),
        ('DEP-WEST', 'CHG-W03', 60.0, 'AVAILABLE'),
        ('DEP-WEST', 'CHG-W04', 60.0, 'MAINTENANCE'),
        ('DEP-EAST', 'CHG-E01', 150.0, 'AVAILABLE'),
        ('DEP-EAST', 'CHG-E02', 60.0, 'AVAILABLE')
) AS seed(depot_code, code, power_kw, status) ON seed.depot_code = depot.code
ON CONFLICT (code) DO NOTHING;
