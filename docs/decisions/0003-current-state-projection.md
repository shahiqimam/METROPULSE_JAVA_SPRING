# 0003 - Current State Projection Table

## Context

The control centre needs the current position and operational state of every vehicle on every screen
refresh. Telemetry history is append-only and grows continuously, so answering "where is each vehicle
now" by scanning history with `DISTINCT ON (vehicle_id) ... ORDER BY recorded_at DESC` gets more
expensive as the table grows, even with an index.

Telemetry also arrives out of order. A late event is valid history but must not move current state
backwards.

## Decision

Keep a `vehicle_current_state` table with one row per vehicle, upserted on ingest in the same
transaction as the observation and the outbox event. The upsert's conflict branch carries a `WHERE`
guard so an older observation is stored historically but never overwrites newer state.

Route progress and route deviation are computed by PostGIS at write time and stored on the row.
Connectivity is derived at read time from telemetry age, because a stored connectivity value would
itself go stale between updates.

## Alternatives

- Query the latest row per vehicle from history on every read. Simple, but the cost grows with
  retained history, and the no-rewind rule would have to be reimplemented at every read site.
- Cache current state in Redis. Redis is not the source of truth, and losing the cache would lose
  the projection; it remains an option as a read cache in front of this table.
- Compute route progress at read time. It would repeat the same PostGIS work on every dashboard poll
  for data that only changes when new telemetry arrives.

## Consequences

- Reads are a single indexed row per vehicle.
- The projection rules live in one place and are covered by integration tests against real PostGIS.
- The projection currently runs inside the ingest request. When the Kafka operational-state consumer
  lands, this write moves behind the consumer; the table contract stays the same, and the consumer
  will need the same no-rewind guard plus an idempotency check on event id.
- Adding derived fields (schedule deviation, headway, next stop) means migrating this table rather
  than changing every read query.
