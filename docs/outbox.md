# Transactional Outbox

## The failure it prevents

A naive ingest writes the observation and then publishes to Kafka:

```text
INSERT telemetry   -> commits
publish to Kafka   -> fails
```

The database now holds an observation nothing downstream will ever hear about. Swapping the order
just moves the problem: publish first and the commit can fail, so consumers act on an observation
that does not exist. A database and a broker are two systems; a single ACID transaction does not
span them.

## How MetroPulse does it

Ingest writes both rows in one database transaction:

```text
BEGIN
  INSERT vehicle_telemetry
  INSERT outbox_event
COMMIT
```

Either both land or neither does. A separate scheduled publisher then does the risky part:

```text
SELECT unpublished outbox rows
send to Kafka
UPDATE published_at
```

If Kafka is down, the rows stay unpublished with `attempt_count` incremented and `last_error`
recorded, and the next run retries them. The API keeps accepting telemetry throughout, because
accepting telemetry never depended on the broker being reachable.

## Table

```text
id            UUID, also the eventId in the published envelope
aggregate_type
aggregate_id  the vehicle, used as the Kafka partition key
event_type
payload       the full event envelope as jsonb
created_at
published_at  NULL until published
attempt_count
last_error
```

A partial index covers the publisher's query, so finding unpublished rows does not scan published
history:

```sql
CREATE INDEX idx_outbox_event_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
```

## What it does and does not guarantee

It guarantees that a committed observation will eventually be published. It does not guarantee
single publication: a publisher that sends successfully and then dies before updating `published_at`
will send that event again on the next run.

That is deliberate. Making publication exactly-once would cost far more than making consumers
idempotent, which MetroPulse does instead — see the `processed_event` ledger in [kafka.md](kafka.md).
The pairing is the standard one: at-least-once publication plus idempotent consumers gives
effectively-once processing.

## Ordering

The publisher sends in `created_at` order and keys by vehicle, so a vehicle's events reach the same
partition in the order they were accepted. A consumer can still see them out of order after a retry,
which is why the projection carries its own no-rewind guard rather than trusting delivery order.

## What the tests prove

`OutboxPublisherIntegrationTest` drives the real publisher with a producer that cannot reach a
broker, then with one that can:

- telemetry is still accepted while publishing is impossible, because the event is committed with the
  observation rather than sent from inside the request
- a failed send leaves the row unpublished and records why, rather than dropping it
- repeated failures keep counting instead of giving up
- the backlog drains when the producer recovers, in the order it was written, and the recorded error
  is cleared
- an already-published event is never sent twice

Failure is injected at the producer rather than by stopping a broker, so this says nothing about how
a real Kafka client behaves during an outage — reconnection, request timeouts and metadata refresh
are the client's business. The broker path itself is covered separately against an embedded Kafka.

## Not yet built

- The publisher is single-instance; two backends would both poll the same rows. Claiming rows with
  `FOR UPDATE SKIP LOCKED` is the usual fix.
- Rows are never pruned after publication.
- There is no alert on a growing unpublished backlog, which is the signal that Kafka is unreachable.
