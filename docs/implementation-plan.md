# How the work was sequenced

This was the build order, kept because the order is part of the design rather than an accident of it.
For what exists today, and what does not, see [status-and-roadmap.md](status-and-roadmap.md) — that
is the live document; this one is history.

## One vertical slice at a time

Every slice went all the way down: migration, domain, service, API, tests, and then a live run against
the Docker stack before the next one started. No slice was "finished" because its tests passed.

1. **Foundation.** Maven modules, Flyway baseline, Compose stack, Angular shell.
2. **Authentication.** JWT access tokens, rotating hashed refresh tokens, roles, URL rules.
3. **Static schedule.** Agencies, routes, stops, calendars, trips, stop times, with PostGIS geometry.
4. **Telemetry ingest.** Validation, ingest key, known-vehicle checks, duplicate handling, immutable
   history.
5. **Outbox and Kafka.** Observation and event committed together; published by a scheduled publisher;
   consumed idempotently.
6. **Operational state.** One row per vehicle, the no-rewind rule, route progress and deviation from
   PostGIS, connectivity derived at read time.
7. **Headway and bunching.** Spacing between consecutive vehicles, persistence windows, hysteresis.
8. **Alerts and incidents.** Fingerprint deduplication enforced by a partial unique index; a single
   incident transition table; an append-only timeline.
9. **Realtime.** STOMP over WebSocket, authenticated in the CONNECT frame, with REST as the baseline.
10. **EV.** Depots, chargers, sessions, and charger reservation under `SELECT ... FOR UPDATE`.
11. **Playback.** Replay over immutable history that writes nothing operational.
12. **Analytics.** Regularity, alert volumes, incident timings, EV state.
13. **Production stack and CI.** nginx edge, prod-style Compose, Jenkins pipeline, smoke test.
14. **Schedule adherence.** Stop-arrival detection, schedule deviation, punctuality, and the three
    alert types that depend on them.
15. **Staged schedule import.** An upload became a preview and a separate decision.

## What the order got wrong

Worth recording, because the sequencing itself caused two of the project's larger corrections.

**The simulator was built before the schedule mattered.** It circled the route shape continuously,
which was a perfectly good model for headway and bunching — the slices that existed at the time.
When schedule adherence arrived, that model had to be replaced wholesale: vehicles had to run trips,
stand at stops, and lose time for real. The headway calculation's "treat the route as a loop"
assumption had to go with it.

**The seeded timetable was never run by anything.** It allowed thirty minutes for a 1.5 km route —
under 3 km/h — and nothing disagreed with it for twelve slices, because nothing had to keep it. The
first component that did exposed it immediately.

Both are the same lesson: a reference nothing is measured against is not verified, however many tests
read it.

## Feature map

- **Authentication** — JWT login, refresh, roles, protected APIs
- **Static schedule** — agency, routes, stops, trips, stop times, GTFS-style staged import
- **Telemetry** — validated observations and immutable history
- **Outbox / Kafka** — reliable publishing after database commit
- **Operations** — current state, progress, schedule deviation, connectivity
- **Headway** — leader/follower spacing, bunching, excessive gaps
- **Alerts / incidents** — deduplicated alerts and a controlled incident workflow
- **Realtime** — REST baseline plus WebSocket deltas
- **EV** — depots, chargers, sessions, battery warnings, reservation locking
- **Playback** — historical replay isolated from live state
- **Analytics** — punctuality, regularity, incidents, EV metrics
- **DevOps** — Docker, nginx, Jenkins, smoke tests
