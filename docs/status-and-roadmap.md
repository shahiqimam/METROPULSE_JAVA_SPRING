# Status

Last updated: 2026-09-18

What is built, what is verified, and what is not — kept honest rather than aspirational.

## Built

### Ingest and events

- Telemetry ingest with Bean Validation, ingest-key authentication, known-vehicle checks and
  duplicate `sourceEventId` handling
- Transactional outbox: observation and event committed together, published by a scheduled publisher
- Kafka producer and `operational-state` consumer, keyed by vehicle across 3 partitions
- Idempotent consumption via `processed_event`, claimed in the same transaction as the work
- Bounded retries and a dead-letter topic for events that can never be applied

### Operational state

- `vehicle_current_state`, one row per vehicle, written by the consumer
- No-rewind rule: a late event is stored historically but never moves current state backwards
- PostGIS route progress (`ST_LineLocatePoint`) and deviation in meters (`ST_Distance` on geography)
- Connectivity (ONLINE/STALE/OFFLINE) derived at read time from telemetry age
- Headway between consecutive vehicles, with a documented reference-speed fallback for stopped
  vehicles
- Bunching and excessive-gap rules with a 90-second persistence window, confirmation and recovery

### Alerts and incidents

- Alert engine with fingerprint deduplication (partial unique index), per-type persistence and
  recovery windows, and hysteresis on deviation, battery and capacity
- Six alert types: telemetry offline, route deviation, bunching, excessive gap, low battery, over
  capacity
- Acknowledge and close, with status never settable directly through the API
- Incident workflow with a single transition table, append-only timeline, and actor taken from the
  authenticated principal

### EV, playback, analytics

- Depots, chargers and charging sessions
- Charger reservation under `SELECT ... FOR UPDATE`, with unique partial indexes as the database's
  own statement of the rule
- Playback over immutable history, writing nothing operational
- Analytics: service regularity, alert counts by type, incident timings, EV and battery state

### Platform

- JWT access tokens, rotating hashed refresh tokens, five roles, URL-based authorisation
- WebSocket/STOMP broadcasts authenticated in the CONNECT frame, with REST as baseline and polling as
  fallback
- Angular control centre: network map from stored PostGIS geometry, fleet list, headway panel, alerts
  panel, login and route guard
- Simulator driving the seeded route geometry with eight reproducible scenarios and no overtaking
- Development and production-style Compose stacks, nginx edge, Jenkins pipeline, smoke-test script
- Maven Wrapper; 14 Flyway migrations

## Verified

Everything below was run, not assumed.

- `./mvnw clean verify` → BUILD SUCCESS: **217 backend + 21 simulator tests**
- Integration tests run the full migration set against real PostgreSQL/PostGIS
- Kafka consumer, redelivery and dead-lettering exercised against an in-process broker
- Charger concurrency test fails when `FOR UPDATE` is removed — the check that makes it meaningful
- Live Docker stack: telemetry flows ingest → outbox → Kafka → consumer → state; 9,834 events
  recorded in `processed_event` with the outbox draining to single digits
- `NORMAL_OPERATION` reports 0.00 m deviation fleet-wide; `ROUTE_DEVIATION` reports 179.66 m against a
  requested 180 m offset
- `BUNCHING` produces a real pack: a vehicle queued 12 m behind its leader classified BUNCHING, a
  1005 m hole classified EXCESSIVE_GAP, both sustained past the persistence window
- `TELEMETRY_LOSS` drives a vehicle to OFFLINE at 62 s while the rest stay ONLINE
- Alerts raised, acknowledged and closed through the API; a second close returns 409
- Incident workflow end to end, including a refused MITIGATING → CANCELLED transition
- Charger reservation returns 409 CHARGER_NOT_AVAILABLE on the second claim
- JWT login, role enforcement (viewer POST → 403), and WebSocket streaming through nginx
- Production-style stack: only nginx published, secrets required, **smoke test 17/17**
- nginx re-resolves upstreams: backend forced onto a new container IP (172.28.0.6 → 172.28.0.9) with
  nginx left running, requests kept succeeding

## Not built

- **Punctuality and schedule deviation.** Needs stop-arrival detection, which needs the simulator to
  run scheduled trips rather than a continuous loop. `VEHICLE_LATE`, `VEHICLE_EARLY` and `LONG_DWELL`
  alerts depend on the same work. Analytics reports regularity instead, under its own name.
- **GTFS-style import.** The schedule model exists and is seeded by migration; there is no upload,
  validation or staged activation path.
- **Playback, incident, EV and analytics screens.** The APIs exist; the dashboard shows vehicles,
  headway and alerts only.
- **Frontend tests.** None. The backend is well covered; the Angular app is not.
- **Redis.** Running in both stacks and used by nothing. It was provisioned for caching and rule
  counters that PostgreSQL has handled adequately so far. Better to say so than to add a decorative
  cache.
- **Retention.** Policy documented, nothing prunes.
- **Horizontal scale.** Single instance: two backends would contend on the outbox publisher
  (`FOR UPDATE SKIP LOCKED`) and each broadcast to only their own subscribers (broker relay).
- **TLS**, log aggregation, platform metrics, rate limiting on login.
- **Jenkins** has not run on a real instance; each stage's commands were validated by hand.

## Environment limitation

Testcontainers is the default path for integration tests and is what CI should exercise. On this
development machine docker-java cannot negotiate an API version with Docker Engine 29 (HTTP 400)
although the Docker CLI works, so the suite was run against a real PostGIS database supplied through
`METROPULSE_TEST_DB_URL`. Real PostGIS behaviour was exercised; the container-start path itself was
not.

## Next

1. Trip-aware simulator movement, then stop-arrival detection — unblocks punctuality and three alert
   types.
2. GTFS import with staged activation.
3. Playback and analytics screens.
4. Frontend tests.
5. Outbox failure test: Kafka down, telemetry still commits, publisher drains the backlog on
   recovery.
