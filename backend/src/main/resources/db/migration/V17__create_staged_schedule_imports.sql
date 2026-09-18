-- Staged schedule imports: a feed is reviewed before it replaces the one in service.
--
-- Until now an upload was applied the moment it parsed. That is the wrong shape for the one piece of
-- data every operational calculation is measured against: route progress, headway, deviation and
-- punctuality all compare a vehicle against the schedule, so replacing it changes the meaning of
-- every number on the screen. A planner needs to see what would change before it does.
--
-- The uploaded files are kept rather than the parsed result. Activation re-parses and re-validates
-- them, so a feed that was staged against one state of the database cannot be applied blindly to
-- another - and what is applied is provably what was uploaded, not a transformation of it that
-- nobody reviewed.

CREATE TABLE schedule_import (
    id BIGSERIAL PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('STAGED', 'ACTIVATED', 'DISCARDED')),
    uploaded_by TEXT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by TEXT,
    activated_at TIMESTAMPTZ,
    discarded_by TEXT,
    discarded_at TIMESTAMPTZ,
    -- What the preview said when it was staged, so a controller can see what was approved rather
    -- than what a fresh comparison would say now.
    preview JSONB NOT NULL,
    -- What activation actually did, which is not the same question as what the preview expected.
    result JSONB,

    CONSTRAINT activated_has_an_actor CHECK (
        (status <> 'ACTIVATED') OR (activated_by IS NOT NULL AND activated_at IS NOT NULL)
    ),
    CONSTRAINT discarded_has_an_actor CHECK (
        (status <> 'DISCARDED') OR (discarded_by IS NOT NULL AND discarded_at IS NOT NULL)
    )
);

CREATE INDEX idx_schedule_import_status ON schedule_import (status, uploaded_at DESC);

CREATE TABLE schedule_import_file (
    import_id BIGINT NOT NULL REFERENCES schedule_import(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    content BYTEA NOT NULL,

    PRIMARY KEY (import_id, file_name)
);
