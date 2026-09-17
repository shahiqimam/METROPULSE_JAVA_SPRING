# Kafka

## Topics

```text
metropulse.telemetry.v1        telemetry events published from the outbox
metropulse.telemetry.v1.DLT    events the operational-state consumer cannot apply
```

Both are created with 3 partitions and a replication factor of 1, which suits a single-broker
development stack. A real deployment would raise the replication factor.

## Partitioning

Telemetry is keyed by `vehicleId`. Kafka guarantees ordering only within a partition, so keying by
vehicle means one vehicle's events stay in order relative to each other. Ordering across vehicles is
not guaranteed, and MetroPulse does not need it: current state is per vehicle.

That also caps useful parallelism. Three partitions means at most three consumers in the group do
work; a fourth would sit idle.

## Event envelope

```json
{
  "eventId": "uuid",
  "eventType": "VehicleTelemetryRecorded",
  "eventVersion": 1,
  "occurredAt": "2026-09-16T10:15:01Z",
  "aggregateId": "BUS-042",
  "payload": {
    "sourceEventId": "sim-evt-00123",
    "vehicleId": "BUS-042",
    "recordedAt": "2026-09-16T10:15:00Z",
    "receivedAt": "2026-09-16T10:15:01Z",
    "latitude": 40.7152,
    "longitude": -73.9980,
    "speedKph": 24.0,
    "headingDegrees": 90.0,
    "occupancyEstimate": 30,
    "batteryPercent": 78
  }
}
```

JSON is used for readability rather than compactness. `eventId` is what consumers deduplicate on;
`sourceEventId` is the vehicle's own id for the observation and is what ingest deduplicates on. They
are different identities and both matter.

Both timestamps travel in the payload: a consumer needs `recordedAt` and `receivedAt` to decide
whether an observation is newer than the state it already holds, and it cannot ask the database for
them without defeating the point of consuming an event.

## Producing

Events are not produced during the ingest request. Ingest writes the observation and an outbox row
in one transaction, and a scheduled publisher sends unpublished rows to Kafka. See
[outbox.md](outbox.md).

## Consuming

`VehicleStateConsumer` subscribes in the group `metropulse-operational-state` and hands each record
to `TelemetryEventHandler`, which projects it into current vehicle state.

The listener and the handler are separate beans deliberately. `@Transactional` works through a proxy,
so a listener calling a transactional method on itself would run outside a transaction, and the
idempotency claim would commit independently of the projection write.

### At-least-once and idempotency

Kafka delivery is at-least-once. After a rebalance, a retry, or a restart before the offset was
committed, a consumer can legitimately see the same event again.

Every consumer claims an event id in `processed_event` in the same transaction as its own writes:

```sql
INSERT INTO processed_event (event_id, consumer_name) VALUES (?, ?)
```

A unique violation means this consumer already applied the event, so it returns without doing
anything. Because the claim and the work share a transaction, a failure rolls back both, and the
redelivery that follows is applied exactly once in effect.

This is not exactly-once delivery. It is at-least-once delivery with an idempotent consumer, which is
what MetroPulse claims.

### Failure handling and the DLT

`KafkaConsumerConfig` installs a `DefaultErrorHandler`:

- Transient failures (the database is briefly unavailable) are retried twice, two seconds apart.
- Permanent failures are not retried at all: `MalformedEventException` and
  `UnknownVehicleInEventException` are registered as non-retryable, because no amount of retrying
  turns a malformed event into a valid one.

Either way the event ends on `metropulse.telemetry.v1.DLT`, keeping its original partition number so
one vehicle's failed events stay together. The point is that the partition keeps moving: a stuck
partition would stall every vehicle whose events hash to it, not just the vehicle in the bad event.

Nothing consumes the DLT yet. Draining and reprocessing it is manual today.

## Configuration

| Property | Environment variable | Default |
| --- | --- | --- |
| `metropulse.outbox.topic` | `METROPULSE_OUTBOX_TOPIC` | `metropulse.telemetry.v1` |
| `metropulse.operations.consumer-enabled` | `METROPULSE_OPERATIONS_CONSUMER_ENABLED` | `true` |
| `metropulse.operations.consumer-group` | `METROPULSE_OPERATIONS_CONSUMER_GROUP` | `metropulse-operational-state` |

Offsets are committed by the container rather than by the client's auto-commit timer
(`enable-auto-commit: false`), so an offset advances after the listener returns rather than on a
timer that can run ahead of the work.

## Tests

- `TelemetryEventHandlerIntegrationTest` — handler behaviour against real PostgreSQL: idempotent
  replay, the no-rewind rule, superseded events still being marked processed, and poison messages.
- `VehicleStateConsumerKafkaIntegrationTest` — the same consumer through an in-process Kafka broker:
  an event arriving over the wire becomes state, a redelivered event is applied once, and both kinds
  of poison message land on the DLT without blocking the events behind them.
