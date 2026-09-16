# Implementation Plan

## Effort Estimate

This is a large portfolio system. A realistic solo build is 18-30 focused work days for a credible MVP and 45-70 days for the full specification with polished docs, CI, screenshots, Docker verification, and interview notes.

Expected Codex usage:

- Phase 0-2: 1-2 reset windows
- Phase 3-6: 2-4 reset windows
- Phase 7-10: 2-4 reset windows
- Phase 11-13 polish: 1-2 reset windows

The total project is likely 6-12 substantial Codex work sessions/resets depending on how much is generated, verified, and revised in one run.

## Phase 0 Foundation

- [x] Repository skeleton
- [x] Maven parent with backend and simulator modules
- [x] Spring Boot backend entry point
- [x] Java simulator entry point
- [x] Flyway baseline migration
- [x] Docker Compose infrastructure draft
- [ ] Maven verification
- [ ] Docker infrastructure verification
- [ ] Angular CLI project completion

## Next Vertical Slice

Build telemetry ingestion correctly:

1. vehicle lookup by fleet number
2. request validation and ingest-key validation
3. telemetry persistence with PostGIS point
4. outbox event persisted in the same transaction
5. duplicate source event behavior
6. unit tests and Testcontainers integration test

## Feature Map

- Authentication: JWT login, refresh, roles, and protected APIs
- Static schedule: agency, routes, stops, trips, stop times, GTFS-style import
- Telemetry: validated vehicle observations and immutable history
- Outbox/Kafka: reliable event publishing after database commit
- Operations: current vehicle state, progress, schedule deviation, connectivity
- Headway: leader/follower detection, bunching, excessive gaps
- Alerts/incidents: deduplicated alerts and controlled incident workflow
- Realtime: REST baseline plus WebSocket deltas
- EV: depots, chargers, sessions, battery warnings, reservation locking
- Playback: historical telemetry replay isolated from live state
- Analytics: punctuality, headways, incidents, and EV metrics
- DevOps: Docker, Nginx, Jenkins, smoke tests
