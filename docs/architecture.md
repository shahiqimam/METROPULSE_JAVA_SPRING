# MetroPulse Architecture

MetroPulse is a modular monolith. Capability packages own their API, application services, domain logic, persistence mappings, and tests.

## Runtime View

```text
Angular Control Center
        |
        v
Spring Boot API
        |
        +--> PostgreSQL/PostGIS
        +--> Redis
        +--> Kafka
        +--> WebSocket/STOMP

Java Simulator --> Telemetry Ingestion API
```

## Event Flow

```text
Telemetry Controller
  -> Telemetry Application Service
  -> VehicleTelemetry row
  -> OutboxEvent row
  -> Outbox Publisher
  -> Kafka metropulse.telemetry.v1
  -> Operational State Consumer
  -> VehicleOperationalState projection
  -> Alerts / WebSocket updates
```

## Current Build State

The repository has Phase 0 scaffolding only. The telemetry endpoint currently validates request shape and returns an accepted response; persistence and outbox behavior are the next backend vertical slice.
