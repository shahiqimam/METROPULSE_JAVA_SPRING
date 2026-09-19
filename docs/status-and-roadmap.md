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
- Headway between consecutive vehicles, open-ended along the route, with a documented
  reference-speed fallback for stopped vehicles
- Bunching and excessive-gap rules with a 90-second persistence window, confirmation and recovery
- Stop-arrival detection: proximity plus low speed against the trip's own stop times, one call per
  trip and stop, with dwell closed on departure
- Schedule deviation per vehicle, carried forward from the last call rather than interpolated;
  measured against planned departure at a trip's origin

### Alerts and incidents

- Alert engine with fingerprint deduplication (partial unique index), per-type persistence and
  recovery windows, and hysteresis on deviation, battery and capacity
- Nine alert types: telemetry offline, route deviation, bunching, excessive gap, low battery, over
  capacity, running late, running early, long dwell
- Acknowledge and close, with status never settable directly through the API
- Incident workflow with a single transition table, append-only timeline, and actor taken from the
  authenticated principal

### EV, playback, analytics

- Depots, chargers and charging sessions
- Charger reservation under `SELECT ... FOR UPDATE`, with unique partial indexes as the database's
  own statement of the rule
- Playback over immutable history, writing nothing operational
- Analytics: punctuality, service regularity, alert counts by type, incident timings, EV and battery
  state

### Platform

- JWT access tokens, rotating hashed refresh tokens, five roles, URL-based authorisation; a planner
  may stage and review a schedule feed but only an administrator may put one into service
- WebSocket/STOMP broadcasts authenticated in the CONNECT frame, with REST as baseline and polling as
  fallback
- GTFS-style import in two steps: an upload parses, validates and stages a preview of what would
  change; a separate decision activates or discards it, with who did which recorded
- Angular control centre with six screens: network (map, fleet, headway, alerts), incidents, EV,
  analytics (punctuality and regularity), playback and schedule review, plus login and route guard
- Simulator running scheduled trips on the seeded geometry, at the speed the timetable implies, with
  eight reproducible scenarios and no overtaking
- Development and production-style Compose stacks, nginx edge, Jenkins pipeline, smoke-test script
- Maven Wrapper; 17 Flyway migrations
- JDBC rather than JPA, deliberately and with an ADR; structured ECS logs carrying the request id
  that `RequestIdFilter` generates

## Verified

Everything below was run, not assumed.

- `./mvnw clean verify` → BUILD SUCCESS: **293 backend + 25 simulator tests**
- `npm run test` → **64 frontend tests**, headless Chrome, with every screen rendered against a
  stubbed API rather than tested only through its service layer
- Integration tests run the full migration set against real PostgreSQL/PostGIS
- Kafka consumer, redelivery and dead-lettering exercised against an in-process broker
- Charger concurrency test fails when `FOR UPDATE` is removed — the check that makes it meaningful
- Live Docker stack: telemetry flows ingest → outbox → Kafka → consumer → state; 9,834 events
  recorded in `processed_event` with the outbox draining to single digits
- `NORMAL_OPERATION` reports 0.00 m deviation fleet-wide; `ROUTE_DEVIATION` reports 179.66 m against a
  requested 180 m offset
- `BUNCHING` produces a real pack: a vehicle queued 12 m behind its leader classified BUNCHING, a
  1005 m hole classified EXCESSIVE_GAP, both sustained past the persistence window
- Stop arrivals recorded live from simulated telemetry at all five stops of the pattern, with dwells
  matching the seeded 30-second stop times and deviations of +2 to +5 seconds once the fleet settles
- `/analytics/punctuality` from that history: 12 calls measured, 100% on time
- Live headway of 76-91 seconds against the route's 83-second target, measured by the backend from
  positions the simulator never labelled - the closest thing to an independent check that the
  timetable, the simulator and the headway model agree
- `LONG_DWELL`, `VEHICLE_EARLY` and `BUNCHING` raised from real movement during the fleet's startup
  transient, then closed as RECOVERED once the service settled
- `TELEMETRY_LOSS` drives a vehicle to OFFLINE at 62 s while the rest stay ONLINE
- Alerts raised, acknowledged and closed through the API; a second close returns 409
- Incident workflow end to end, including a refused MITIGATING → CANCELLED transition
- Charger reservation returns 409 CHARGER_NOT_AVAILABLE on the second claim
- JWT login, role enforcement (viewer POST → 403), and WebSocket streaming through nginx
- Production-style stack: only nginx published, secrets required, **smoke test 17/17**
- nginx re-resolves upstreams: backend forced onto a new container IP (172.28.0.6 → 172.28.0.9) with
  nginx left running, requests kept succeeding

## Not built

- **Frontend component tests.** The status rules, session service and HTTP interceptor are covered;
  the components themselves are not rendered in tests.
- **Redis.** Running in both stacks and used by nothing. It was provisioned for caching and rule
  counters that PostgreSQL has handled adequately so far. Better to say so than to add a decorative
  cache.
- **Retention.** Policy documented, nothing prunes.
- **Horizontal scale.** Single instance: two backends would both poll the same outbox rows, which
  needs claiming with `FOR UPDATE SKIP LOCKED`, and each would broadcast to only their own
  subscribers, which needs a broker relay.
- **Log aggregation and platform metrics.** Logs are structured ECS JSON carrying the request id,
  which is the half that belongs in the application; nothing collects or scrapes them.
- **TLS** and rate limiting on login.
- **Jenkins** has not run on a real instance; each stage's commands were validated by hand.

## Environment limitation

Testcontainers is the default path for integration tests and is what CI should exercise. On this
development machine docker-java cannot negotiate an API version with Docker Engine 29 (HTTP 400)
although the Docker CLI works, so the suite was run against a real PostGIS database supplied through
`METROPULSE_TEST_DB_URL`. Real PostGIS behaviour was exercised; the container-start path itself was
not.

## Next

1. Screenshots for the README. The docs carry Mermaid diagrams and a demo walkthrough; there are no
   images of the running control centre, and capturing them needs a browser and a person.
2. Retention. The policy is documented; nothing prunes.
3. Horizontal scale: claiming outbox rows with `FOR UPDATE SKIP LOCKED`, and a broker relay so two
   backends do not each broadcast to only their own subscribers.

## What live running caught that the tests did not

Kept because the pattern is the point: each of these passed a green suite and was wrong anyway.

- Vehicles never called at stops, because the simulator drove past them. Arrival detection was
  correct and had nothing to detect.
- The seeded timetable allowed 30 minutes for a 1,459 m route. Nothing had ever had to run it, so
  nothing had ever disagreed with it.
- Treating the route as a loop paired the vehicle approaching the far terminal with one sitting at
  the near one and called it severe bunching.
- Measuring a trip's origin as an arrival made every departure read as early running, because a
  vehicle waiting at its terminal has "arrived" whenever it pulled in.
- Vehicles laying over at a terminal appeared in the headway calculation as pairs metres apart.
- `AnalyticsService` read the wall clock while its tests wrote history at a fixed one. The suite
  passed for as long as the two stayed within 24 hours of each other, then began failing on a date
  rather than a change.
