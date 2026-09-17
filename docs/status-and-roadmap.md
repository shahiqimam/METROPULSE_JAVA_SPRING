# MetroPulse Status and Roadmap

Last updated: 2026-09-17

This document tracks the repository state against the MetroPulse project brief. The brief is reference material for scope and acceptance criteria; the active work is driven by user requests and verified repository state.

## Current Implementation

### Backend

Implemented:

- Spring Boot backend module
- health endpoint at `GET /api/v1/health`
- Flyway migrations for the foundation schema and development vehicle seed
- PostgreSQL/PostGIS schema for users, vehicles, telemetry, and outbox events
- telemetry ingest endpoint at `POST /api/v1/telemetry/ingest`
- ingest key validation through `X-Ingest-Key`
- Bean Validation on telemetry payloads
- duplicate telemetry handling by `source_event_id`
- known-vehicle validation
- transactional telemetry insert plus outbox event insert
- standard API error handling with request IDs
- latest vehicle telemetry read endpoint at `GET /api/v1/telemetry/vehicles/latest`
- Docker Compose development credentials for authenticated read APIs

Not yet implemented:

- JWT authentication and refresh tokens
- role-based authorization
- static schedule model and importer
- outbox publisher to Kafka
- operational state projection
- alerts, incidents, EV charging, playback, analytics
- WebSocket realtime updates
- backend unit and integration test coverage beyond the placeholder test

### Simulator

Implemented:

- Java Spring Boot simulator module
- deterministic scheduled telemetry emitter
- configurable ingest URL, ingest key, seed, interval, and vehicle IDs
- synthetic route-like movement around a New York City development path
- Docker Compose wiring to send simulator events into the backend

Not yet implemented:

- named scenarios such as bunching, route deviation, telemetry loss, long dwell, low battery, recovery
- multi-route and multi-vehicle fleet simulation beyond configurable IDs
- simulator test coverage

### Frontend

Implemented:

- Angular standalone application
- operations dashboard route
- live latest-telemetry API integration
- configurable API base and Basic Auth credentials
- Docker and Angular dev proxy support for `/api`
- auto-refresh toggle with 10-second polling
- vehicle summary cards and fleet summary metrics
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
- full topic creation/configuration
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

Current limitation:

- Docker Desktop became unavailable after the latest `.dockerignore` change, so the `.dockerignore` optimization has not yet been Docker-rebuilt. Angular build still passes locally.
- Maven is not installed on the host; Java verification has been done through Docker images when Docker is available.

## Recommended Phase Plan

### Phase 0: Foundation Stabilization

Status: mostly complete.

Remaining:

- add Maven wrapper so host Maven is not required
- add meaningful backend smoke/unit tests
- verify `.dockerignore` with Docker once Docker Desktop is back
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

1. Add Maven wrapper and backend test scaffolding.
2. Add authentication with stable seeded operator users and JWT.
3. Add static schedule schema and a small fictional route/stop seed.
4. Expand simulator from one vehicle to multiple scenario-capable vehicles.
5. Implement outbox publisher to Kafka and verify unpublished retry behavior.
