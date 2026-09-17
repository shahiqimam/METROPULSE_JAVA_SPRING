# Operational State Projection

MetroPulse stores every telemetry observation immutably in `vehicle_telemetry`, and keeps one
current-state row per vehicle in `vehicle_current_state`. The control centre reads current state;
history stays available for playback and analytics.

## Where the projection happens

The projection is written by the operational-state Kafka consumer, not by the ingest request.

```text
POST /api/v1/telemetry/ingest
  -> validate ingest key, payload, known vehicle, duplicate source event
  -> INSERT vehicle_telemetry            (immutable history)
  -> INSERT outbox_event                 (same transaction)
  -> COMMIT

outbox publisher -> Kafka metropulse.telemetry.v1

VehicleStateConsumer
  -> claim event id in processed_event   (same transaction)
  -> UPSERT vehicle_current_state        (no-rewind guarded)
  -> COMMIT
```

Deriving state from the published event rather than from the request means current state is rebuilt
from the same events any other consumer sees, and a slow projection cannot slow down ingest. It also
means state is eventually consistent with history: there is a short window after ingest returns 202
where the observation is stored but current state has not caught up yet.

Ingest owns history and the outbox. The consumer owns current state. See [kafka.md](kafka.md) for
delivery semantics and [outbox.md](outbox.md) for why the event is published that way.

## No-rewind rule

Events can arrive late. A late event is still historical evidence, but current state must not move
backwards. The upsert only overwrites state when the incoming observation is at least as recent as
the state already held:

```sql
ON CONFLICT (vehicle_id) DO UPDATE
SET ...
WHERE vehicle_current_state.recorded_at < EXCLUDED.recorded_at
   OR (vehicle_current_state.recorded_at = EXCLUDED.recorded_at
       AND vehicle_current_state.received_at <= EXCLUDED.received_at)
```

Both timestamps are stored: `recorded_at` is when the vehicle observed it, `received_at` is when the
platform accepted it. Ties on `recorded_at` are broken by arrival order.

There are two separate deduplication points, and they guard different things:

- ingest deduplicates on `source_event_id`, the vehicle's own id for the observation, so a simulator
  resend never creates a second history row or a second event;
- the consumer deduplicates on `eventId` in `processed_event`, so Kafka redelivery never applies the
  same event twice.

## Route progress

Each vehicle can be assigned to a route (`vehicle.assigned_route_id`). When it is, PostGIS projects
the observed point onto the route geometry:

```sql
ST_LineLocatePoint(route.geometry, observation.location)
```

The result is normalised position along the route shape: `0.0` is the start of the line, `1.0` is the
end, `0.72` is roughly 72% along the shape. It is a position along the geometry, not a distance
travelled and not a schedule measure.

An unassigned vehicle stores `NULL` route progress rather than a fabricated `0.0`.

## Route deviation

```sql
ST_Distance(route.geometry::geography, observation.location::geography)
```

Casting to `geography` makes the distance metric, so the stored value is meters rather than degrees.
The dashboard highlights vehicles more than 100 m from their route shape. That 100 m figure is a
MetroPulse project threshold, not a transit-industry standard, and the alerting side of it
(persistence period plus hysteresis on recovery) is still to be built.

## Connectivity

Connectivity is derived at read time from the age of the most recent observation, not stored,
because a stored value would itself go stale:

```text
ONLINE   age <= 15s
STALE    15s < age <= 60s
OFFLINE  age > 60s
```

The age is measured by the database clock (`now() - recorded_at`) and classified in
`ConnectivityState.classify`, which keeps the thresholds unit-testable.

These thresholds describe one vehicle. A fleet that is entirely OFFLINE usually means telemetry
processing is unhealthy, not that every bus stopped reporting; the UI must not conflate the two.

## Read API

`GET /api/v1/telemetry/vehicles/latest` returns, per vehicle: position, speed, heading, occupancy,
battery, route code, route progress, route deviation in meters, telemetry age in seconds and
connectivity state.

## Tests

- `ConnectivityStateTest` — threshold boundaries, including the exact 15 s and 60 s edges.
- `TelemetryIngestionIntegrationTest` — accepted/duplicate/unknown-vehicle/bad-key behaviour, the
  event envelope, that the stored location is an SRID 4326 point, and that ingest does not write
  current state until the event is consumed.
- `TelemetryEventHandlerIntegrationTest` — idempotent replay, the no-rewind rule, and poison messages.
- `VehicleStateConsumerKafkaIntegrationTest` — the same path through an in-process Kafka broker,
  including dead-lettering.
- `VehicleRouteProjectionIntegrationTest` — progress at the start, middle and end of the seeded M42
  shape, deviation in meters for an off-route position, unassigned vehicles, and connectivity.

Both integration tests run the full Flyway migration set against real PostgreSQL/PostGIS. See
`docs/testing.md` for how to run them.
