-- Incidents: the human side of operations.
--
-- An alert is raised by a rule and closes when the rule stops firing. An incident is opened by a
-- controller, moves through a workflow under their control, and never closes itself. They are
-- deliberately separate: an automated condition and a piece of operational work have different
-- lifecycles, and conflating them means either rules that cannot be dismissed or work that vanishes
-- when a measurement changes.

CREATE TABLE incident (
    id BIGSERIAL PRIMARY KEY,
    incident_number VARCHAR(40) NOT NULL UNIQUE,
    type VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    vehicle_id BIGINT REFERENCES vehicle(id),
    route_id BIGINT REFERENCES route(id),
    opened_by VARCHAR(120) NOT NULL,
    assigned_controller VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    mitigating_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT chk_incident_status CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'MITIGATING', 'RESOLVED', 'CANCELLED')
    ),
    CONSTRAINT chk_incident_severity CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR'))
);

CREATE INDEX idx_incident_status_started_at
    ON incident (status, started_at DESC);

CREATE INDEX idx_incident_vehicle
    ON incident (vehicle_id);

-- Every transition and note, in order.
--
-- This is the audit trail: who did what, when, and what they said about it. Entries are only ever
-- appended - an incident's history is evidence, and evidence that can be edited is not evidence.
CREATE TABLE incident_timeline (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    entry_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20),
    note TEXT,
    actor VARCHAR(120) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_incident_timeline_entry_type CHECK (entry_type IN ('TRANSITION', 'NOTE'))
);

CREATE INDEX idx_incident_timeline_incident
    ON incident_timeline (incident_id, recorded_at);

-- Incident numbers are human-facing: a controller reads one out loud over a radio.
CREATE SEQUENCE incident_number_seq START WITH 1001;
