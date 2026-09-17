# 0004 - Project Operational State From Kafka Events

## Context

Current vehicle state was first written inside the telemetry ingest transaction, next to the
observation and the outbox row. That was simple and atomic, but it put derived work on the ingest
path: every new rule (schedule deviation, headway, bunching, alerting) would make accepting telemetry
slower, and none of that work would be reproducible from the event stream that other consumers see.

The events already existed. The outbox publishes `VehicleTelemetryRecorded` to Kafka; nothing
consumed it.

## Decision

Move the projection behind a Kafka consumer. Ingest owns history and the outbox; the
operational-state consumer owns `vehicle_current_state`.

Because Kafka delivery is at-least-once, the consumer claims each event id in a `processed_event`
ledger inside the same transaction as the projection write. Events that can never be applied are
routed to a dead-letter topic after bounded retries.

## Alternatives

- Keep the projection in the ingest transaction. Strongly consistent and simple, but derived work
  grows on the request path, and state cannot be rebuilt from events.
- Consume with manual offset management and no ledger, relying on committing offsets after the write.
  A crash between write and commit still redelivers, so idempotency is needed regardless; the ledger
  makes it explicit and survives offset resets.
- Kafka transactions between consumer and database. Real exactly-once needs a transactional resource
  spanning both, which Kafka transactions do not provide for an external database.

## Consequences

- Current state is eventually consistent with history. There is a window after ingest returns 202
  where the observation exists but state has not caught up. The read API exposes telemetry age, so
  the UI can show freshness rather than pretending it is instant.
- The projection is replayable: clearing `processed_event` and current state and replaying the topic
  rebuilds state.
- Ingest no longer depends on projection logic, so rules can grow without slowing ingestion.
- Two deduplication identities now exist and must not be confused: `sourceEventId` (ingest, per
  observation) and `eventId` (consumers, per published event).
- A backlog on the consumer becomes an operational concern of its own. Consumer lag is not monitored
  yet, and a vehicle whose events are lagging looks stale in the UI even though telemetry arrived.
