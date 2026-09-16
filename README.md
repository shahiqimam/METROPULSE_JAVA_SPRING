# MetroPulse Java Spring

MetroPulse is a synthetic real-time transit operations control center built as an enterprise Java portfolio project.

It models a fictional bus/BRT network with schedule data, live vehicle telemetry, operational state projection, alerting, EV charging operations, historical playback, and analytics.

## Planned Features

- Spring Boot modular monolith backend
- Angular operations-console frontend
- Java telemetry simulator
- PostgreSQL/PostGIS for authoritative data and geospatial queries
- Kafka for event transport
- Redis for short-lived coordination/cache use cases
- Transactional outbox for database-to-Kafka consistency
- REST baseline APIs plus WebSocket/STOMP realtime deltas
- Flyway-managed schema
- JUnit, Mockito, and Testcontainers test strategy
- Docker Compose development stack
- Jenkins CI/CD pipeline

## Initial Phase

This repository currently starts with Phase 0 foundation:

- backend Spring Boot application skeleton
- simulator Java application skeleton
- Docker Compose infrastructure for PostGIS, Kafka, and Redis
- Flyway baseline migration
- architecture and implementation-plan docs

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
- Maven 3.9+
- Node.js 22+
- Docker Desktop

## Development Commands

```bash
mvn verify
docker compose up --build
```

Maven must be installed or a Maven wrapper must be added before backend/simulator verification can run on a fresh machine.

## Synthetic Data Notice

MetroPulse is not a real transit, dispatch, fare-collection, or passenger-information system. All routes, schedules, vehicles, operators, telemetry, incidents, and EV data are fictional.
