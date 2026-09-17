# Operational State Projection

MetroPulse stores every telemetry observation immutably in `vehicle_telemetry`, and keeps one
current-state row per vehicle in `vehicle_current_state`. The control centre reads current state;
history stays available for playback and analytics.

## Where the projection happens today

The projection is written inside the telemetry ingest transaction
(`TelemetryIngestionService.ingest`), in the same transaction as the observation row and the outbox
row. That keeps the three writes atomic: either all of them commit or none do.

This will move behind the Kafka consumer when the operational-state consumer lands (phase 5/6 of the
roadmap). The SQL and the rules below are the part that will move; the table contract will not.

```text
POST /api/v1/telemetry/ingest
  -> validate ingest key, payload, known vehicle, duplicate source event
  -> INSERT vehicle_telemetry            (immutable history)
  -> UPSERT vehicle_current_state        (projection, no-rewind guarded)
  -> INSERT outbox_event                 (published to Kafka later)
  -> COMMIT
```

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

Duplicate `source_event_id` values never reach the projection at all: ingest returns `DUPLICATE`
before any write.

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
  no-rewind rule, and that the stored location is an SRID 4326 point.
- `VehicleRouteProjectionIntegrationTest` — progress at the start, middle and end of the seeded M42
  shape, deviation in meters for an off-route position, unassigned vehicles, and connectivity.

Both integration tests run the full Flyway migration set against real PostgreSQL/PostGIS. See
`docs/testing.md` for how to run them.
