# MetroPulse Status and Roadmap

Last updated: 2026-09-17

This document tracks the repository state against the MetroPulse project brief. The brief is reference material for scope and acceptance criteria; the active work is driven by user requests and verified repository state.

## Current Implementation

### Backend

Implemented:

- Spring Boot backend module
- health endpoint at `GET /api/v1/health`
- Flyway migrations for the foundation schema, development fleet seed, static schedule schema, and schedule seed
- PostgreSQL/PostGIS schema for users, vehicles, telemetry, and outbox events
- telemetry ingest endpoint at `POST /api/v1/telemetry/ingest`
- ingest key validation through `X-Ingest-Key`
- Bean Validation on telemetry payloads
- duplicate telemetry handling by `source_event_id`
- known-vehicle validation
- transactional telemetry insert plus outbox event insert
- scheduled outbox publisher to Kafka topic `metropulse.telemetry.v1`
- standard API error handling with request IDs
- current vehicle state table (`vehicle_current_state`) projected inside the ingest transaction
- no-rewind rule so late events are stored historically without moving current state backwards
- PostGIS route progress (`ST_LineLocatePoint`) and route deviation in meters (`ST_Distance` on geography)
- vehicle-to-route assignment (`vehicle.assigned_route_id`) seeded for the development fleet
- telemetry-age connectivity classification (ONLINE/STALE/OFFLINE)
- current vehicle state read endpoint at `GET /api/v1/telemetry/vehicles/latest`
- Maven Wrapper so the build runs without a host Maven install
- JUnit 5 unit tests and PostgreSQL/PostGIS integration tests
- Docker Compose development credentials for authenticated read APIs
- static route and route-stop read endpoints at `GET /api/v1/routes` and `GET /api/v1/routes/{code}/stops`

Not yet implemented:

- JWT authentication and refresh tokens
- role-based authorization
- GTFS-style schedule importer
- operational state behind the Kafka consumer rather than inside the ingest transaction
- schedule deviation, headway, bunching
- alerts, incidents, EV charging, playback, analytics
- WebSocket realtime updates
- Kafka consumer, MockMvc API, and charger concurrency test coverage

### Simulator

Implemented:

- Java Spring Boot simulator module
- deterministic scheduled telemetry emitter for a multi-vehicle fleet
- movement along the seeded route geometry: distance-based progress, wrapping at the end of the shape
- evenly spaced fleet so headway and bunching are consequences of speed, not scripted outcomes
- scenario behaviour for bunching, route deviation, telemetry loss, long dwell, low battery, multi-incident, and recovery
- configurable ingest URL, ingest key, seed, interval, vehicle IDs, scenario, and route points
- Docker Compose wiring, including scenario, seed, and interval overrides
- unit tests for the route path and every scenario

Not yet implemented:

- multi-route fleet simulation beyond the seeded development route
- schedule-aware movement (trips and stop times rather than a continuous loop)
- dwell at actual stop locations

### Frontend

Implemented:

- Angular standalone application
- operations dashboard route
- live latest-telemetry API integration
- configurable API base and Basic Auth credentials
- Docker and Angular dev proxy support for `/api`
- auto-refresh toggle with 10-second polling
- vehicle summary cards, fleet summary metrics, scheduled route summary, and route stop pattern
- per-vehicle connectivity pill, route progress bar, and route deviation readout
- fleet counters for offline and off-route vehicles
- extracted telemetry API service

Not yet implemented:

- login page and auth flow
- route guards
- map view
- vehicle detail pages
- alerts/incidents/EV/playback/analytics screens
- WebSocket client
- frontend automated tests

### Infrastructure and Docs

Implemented:

- Docker Compose stack for backend, frontend, simulator, PostGIS, Kafka, Redis, Kafka UI
- frontend nginx config for Angular routes and `/api` proxying
- Jenkinsfile foundation
- `.dockerignore` for smaller Docker contexts
- README with current development commands and credentials
- ADR and architecture documentation foundation

Not yet implemented:

- production-style Compose/nginx stack where only nginx is public
- complete Jenkins pipeline stages with passing tests
- production topic configuration beyond the local telemetry topic
- deployment docs and smoke-test automation

## Verified So Far

Verified successfully:

- backend Docker image builds
- simulator Docker image builds
- frontend local Angular production build
- Docker Compose backend health endpoint returns `UP`
- telemetry ingest accepts valid simulator/manual events
- duplicate telemetry returns duplicate status
- missing ingest key returns structured 400 error
- telemetry rows and outbox rows are persisted in Postgres
- simulator emits accepted telemetry events on schedule
- latest telemetry API returns live `BUS-042` data with authentication
- frontend dev proxy reaches the protected backend API
- containerized frontend nginx proxies `/api` to backend successfully
- stable Docker dashboard credentials work: `operator / metropulse-dev-password`
- `.dockerignore` keeps Docker build contexts small; frontend image rebuilt successfully after the optimization
- static schedule migrations apply through Flyway to schema version 4
- development seeds create 4 vehicles, 1 agency, 1 route, 5 stops, 1 trip, and 5 stop times
- seeded route `M42` stores a 5-point PostGIS route geometry
- schedule read APIs return route summary and ordered stop pattern data with authentication
- outbox publisher drains unpublished rows to Kafka; 604 existing rows were marked published with no errors
- Kafka topic `metropulse.telemetry.v1` is created with 3 partitions and contains telemetry envelope messages
- simulator can emit four seeded development vehicles per tick with scenario-specific speed, dwell, occupancy, and battery patterns
- Maven Wrapper bootstraps Maven 3.9.9 and `./mvnw test` passes: 24 backend tests and 1 simulator test
- integration tests run the full Flyway migration set (through V7) against real PostgreSQL/PostGIS
- route progress is 0.0 at the seeded M42 start point, 1.0 at its end point, and in between elsewhere
- off-route positions report deviation in meters, and unassigned vehicles report null rather than a fabricated 0.0
- live Docker stack returns route code, progress, deviation, telemetry age, and connectivity for all four simulated vehicles
- `./mvnw clean verify` passes end to end: 23 backend tests and 19 simulator tests
- under `NORMAL_OPERATION` all four simulated vehicles report 0.00 m route deviation, evenly spaced around the shape
- under `ROUTE_DEVIATION` the affected vehicle reports 179.66 m against a requested 180 m offset, and the rest stay at 0.00 m
- under `TELEMETRY_LOSS` the affected vehicle reaches OFFLINE at 62 s while the rest stay ONLINE
- `METROPULSE_SIMULATOR_SCENARIO` now reaches the container: Compose passes scenario, seed, interval, and ingest key through

Current limitations:

- Testcontainers cannot start containers on this host: docker-java fails API negotiation against Docker Engine 29 with HTTP 400, although the Docker CLI works. Integration tests were therefore run against the Compose stack's real PostGIS database through `METROPULSE_TEST_DB_URL`. The Testcontainers path remains the default and is what CI should exercise.
- The simulator drives a continuous loop of the route shape rather than scheduled trips, so schedule deviation cannot be derived from it yet. Trip-aware movement is needed before punctuality means anything.

## Recommended Phase Plan

### Phase 0: Foundation Stabilization

Status: mostly complete.

Remaining:

- tighten README clone-to-run instructions

Estimated effort: 0.5-1 day.

### Phase 1: Authentication

Build:

- login, refresh, logout, me endpoints
- user table password hashing
- JWT access tokens and refresh tokens
- roles: `ADMIN`, `CONTROLLER`, `FLEET_SUPERVISOR`, `PLANNER`, `VIEWER`
- Angular login, auth service, interceptor, route guard

Estimated effort: 2-3 days.

### Phase 2: Static Schedule and Seed Data

Build:

- agency, route, stop, calendar, trip, stop-time schema
- route geometry and stop location PostGIS indexes
- fictional seed network
- read APIs for routes and vehicles
- dashboard route counts from stored data

Estimated effort: 3-5 days.

### Phase 3: Simulator and Telemetry Expansion

Build:

- multi-vehicle simulation
- scenario model for normal operation, bunching, route deviation, telemetry loss, long dwell, low battery, recovery
- richer simulator configuration and docs

Estimated effort: 2-4 days.

### Phase 4: Kafka and Outbox Publisher

Build:

- outbox polling publisher
- Kafka topic constants/config
- producer publishing `VehicleTelemetryRecorded`
- retry/error handling and `attempt_count`
- tests for DB commit plus unpublished outbox behavior

Estimated effort: 3-5 days.

### Phase 5: Operational State Projection

Build:

- current vehicle state table
- late-event no-rewind rule
- route progress with PostGIS
- connectivity state
- occupancy and battery projection

Estimated effort: 5-8 days.

### Phase 6: Headway, Bunching, and Route Deviation

Build:

- headway calculator
- bunching and excessive-gap rules
- route deviation rules with hysteresis
- unit tests at threshold boundaries

Estimated effort: 4-7 days.

### Phase 7: Alerts and Incidents

Build:

- alert schema and fingerprint deduplication
- incident schema and workflow transitions
- acknowledge/mitigate/resolve APIs
- dashboard alert/incident panels

Estimated effort: 4-7 days.

### Phase 8: Realtime

Build:

- Spring WebSocket/STOMP endpoint
- topic design for vehicles, alerts, incidents
- Angular reconnect strategy
- REST baseline plus WebSocket delta flow

Estimated effort: 3-5 days.

### Phase 9: EV Operations

Build:

- depots, chargers, charging sessions
- charger reservation transaction with locking
- low battery alerts
- EV dashboard panel

Estimated effort: 4-6 days.

### Phase 10: Playback and Analytics

Build:

- playback sessions and frames
- historical telemetry replay API
- punctuality/headway/incident/EV analytics endpoints
- Angular playback and analytics views

Estimated effort: 5-8 days.

### Phase 11: CI/CD and Portfolio Polish

Build:

- meaningful Jenkins stages
- Testcontainers integration tests
- smoke-test scripts
- screenshots, diagrams, interview notes
- limitations and performance notes

Estimated effort: 3-6 days.

## Overall Estimate

A polished portfolio-grade version is likely 5-8 focused weeks if implemented carefully with tests and documentation.

A thinner demo version with authentication, schedule seed, simulator, outbox publishing, state projection, basic alerts, WebSocket updates, and a dashboard can likely be built in 2-3 focused weeks.

Token resets are hard to forecast exactly because they depend on debugging, Docker availability, test failures, and how much documentation is produced. A realistic agent-work estimate is:

- foundation plus dashboard: already several coherent pushes completed
- demo-quality remaining work: roughly 8-15 long coding sessions
- portfolio-quality remaining work: roughly 20-35 long coding sessions

The best working pattern is to keep shipping small vertical slices: schema, API, simulator/frontend usage, verification, docs, commit, push.

## Next Best Slices

1. Move the projection behind a Kafka consumer with an idempotent processed-event table and a DLT.
2. Derive headway between vehicles on the same route from route progress, and add bunching and gap rules.
3. Add schedule deviation against `stop_time` once simulator movement follows trips.
4. Add authentication with stable seeded operator users and JWT.
5. Add publisher retry/backoff tuning and tests around failed Kafka sends.
