# MetroPulse Java Spring

MetroPulse is a synthetic real-time transit operations control center built as an enterprise Java portfolio project.

It models a fictional bus/BRT network with schedule data, live vehicle telemetry, operational state projection, alerting, EV charging operations, historical playback, and analytics.

## Implemented Foundation

- Spring Boot backend with health checks, Flyway migrations, telemetry ingest, duplicate protection, transactional outbox writes, Kafka outbox publishing, and static schedule read API
- Event-driven operational state: a Kafka consumer projects published telemetry into current vehicle state, deduplicating on event id, with bounded retries and a dead-letter topic
- Operational state projection: PostGIS route progress and route deviation in meters, telemetry-age connectivity, and a no-rewind rule for late events
- Angular operations dashboard that reads live vehicle state and scheduled route data through the backend API
- JUnit 5 unit tests plus PostgreSQL/PostGIS integration tests run through the Maven Wrapper
- Java simulator that drives a deterministic fleet along the seeded route geometry, with scenarios for bunching, route deviation, telemetry loss, long dwell, low battery, multi-incident, and recovery
- PostgreSQL/PostGIS, Kafka, Redis, backend, frontend, and simulator wired with Docker Compose
- Development nginx proxy for containerized frontend `/api` calls
- Architecture docs, ADRs, Jenkins pipeline, and environment examples

## Planned Features

- Schedule deviation, headway, and bunching
- Operator authentication and role-based authorization
- WebSocket/STOMP realtime dashboard deltas
- Fleet, route, stop, trip, incident, and charging workflows
- Kafka event consumers
- Historical playback and analytics views
- Broader JUnit, Mockito, Testcontainers, and frontend test coverage
- Production deployment hardening and CI/CD expansion

## Project Layout

```text
backend/       Spring Boot API and processors
frontend/      Angular control-center app
simulator/     Java synthetic telemetry simulator
docs/          Architecture, decisions, and implementation notes
infra/         Nginx and operational scripts
data/          Synthetic schedule/GTFS-style inputs
```

## Local Prerequisites

- Java 21+ (the Maven Wrapper supplies Maven itself)
- Node.js 22+
- Docker Desktop

## Development Commands

```bash
./mvnw test
npm --prefix frontend run build
docker compose up --build
```

Integration tests need a PostgreSQL/PostGIS database; see [docs/testing.md](docs/testing.md).

The Docker development stack exposes:

- Frontend dashboard: http://localhost:4200
- Backend API: http://localhost:18080
- Kafka UI: http://localhost:8085
- Postgres: localhost:5433

Default Docker dashboard credentials are:

```text
username: operator
password: metropulse-dev-password
```

Override them with `METROPULSE_OPERATOR_USERNAME` and `METROPULSE_OPERATOR_PASSWORD` in a local `.env` file.

Vehicle state, including route progress and route deviation, is documented in
[docs/operational-state.md](docs/operational-state.md). The event path is documented in
[docs/outbox.md](docs/outbox.md) and [docs/kafka.md](docs/kafka.md).

The simulator posts fleet telemetry to `POST /api/v1/telemetry/ingest` with the development ingest key, and the dashboard reads current vehicle state from `GET /api/v1/telemetry/vehicles/latest`. Override the active scenario with `METROPULSE_SIMULATOR_SCENARIO`, for example:

```bash
METROPULSE_SIMULATOR_SCENARIO=ROUTE_DEVIATION docker compose up -d simulator --force-recreate
```

Scenarios and simulator configuration are documented in [docs/simulator.md](docs/simulator.md).

## Synthetic Data Notice

MetroPulse is not a real transit, dispatch, fare-collection, or passenger-information system. All routes, schedules, vehicles, operators, telemetry, incidents, and EV data are fictional.
