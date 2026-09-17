-- Idempotency ledger for Kafka consumers.
--
-- Kafka delivery is at-least-once, so a consumer can legitimately see the same event more than once
-- (redelivery after a rebalance, a retry, or a restart before the offset was committed). A consumer
-- records the events it has applied here, in the same transaction as its own writes, so replaying an
-- event is a no-op rather than a second application.

CREATE TABLE processed_event (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer_name)
);

CREATE INDEX idx_processed_event_processed_at
    ON processed_event (processed_at);
