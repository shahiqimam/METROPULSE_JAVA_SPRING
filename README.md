# MetroPulse Java Spring

MetroPulse is a synthetic real-time transit operations control center built as an enterprise Java portfolio project.

It models a fictional bus/BRT network with schedule data, live vehicle telemetry, operational state projection, alerting, EV charging operations, historical playback, and analytics.

## Implemented Foundation

- Spring Boot backend with health checks, Flyway migrations, telemetry ingest, duplicate protection, transactional outbox writes, Kafka outbox publishing, latest-vehicle telemetry read API, and static schedule read API
- Angular operations dashboard that reads live telemetry and scheduled route data through the backend API
- Java simulator that emits deterministic synthetic vehicle telemetry into the backend on a schedule
- PostgreSQL/PostGIS, Kafka, Redis, backend, frontend, and simulator wired with Docker Compose
- Development nginx proxy for containerized frontend `/api` calls
- Architecture docs, ADRs, Jenkins pipeline, and environment examples

## Planned Features

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

- Java 21+
- Maven 3.9+ or Docker for Maven-based image builds
- Node.js 22+
- Docker Desktop

## Development Commands

```bash
npm --prefix frontend run build
docker compose up --build
```

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

The simulator posts to `POST /api/v1/telemetry/ingest` with the development ingest key and the dashboard reads latest vehicle positions from `GET /api/v1/telemetry/vehicles/latest`.

## Synthetic Data Notice

MetroPulse is not a real transit, dispatch, fare-collection, or passenger-information system. All routes, schedules, vehicles, operators, telemetry, incidents, and EV data are fictional.
