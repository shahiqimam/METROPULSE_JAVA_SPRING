# MetroPulse

Real-time transit operations platform: Java 21, Spring Boot, Kafka, PostgreSQL/PostGIS, Redis and
Angular. Synthetic fleet telemetry, PostGIS route projection, headway and bunching detection,
deduplicated alerts, incident workflow, EV charging, historical playback and analytics.

**All data is synthetic.** MetroPulse is not a real transit, dispatch, fare-collection or
passenger-information system. Every route, schedule, vehicle, operator, observation, incident and
battery reading is fictional.

---

## What it does

A simulated fleet runs a route. Telemetry arrives, is stored, published, projected, measured, and
turned into things a controller acts on.

```text
Java simulator
      │  POST /api/v1/telemetry/ingest  (X-Ingest-Key)
      ▼
Spring Boot ingest ──┬─► vehicle_telemetry   immutable history
                     └─► outbox_event        same transaction
                              │
                     outbox publisher
                              ▼
                     Kafka  metropulse.telemetry.v1   (keyed by vehicle)
                              │
              operational-state consumer  (idempotent, DLT on poison)
                              ▼
                     vehicle_current_state
                     ├─ PostGIS route progress and deviation
                     ├─ connectivity from telemetry age
                     ├─ headway, bunching, excessive gap
                     └─ alerts ─► incidents
                              ▼
                  REST baseline + WebSocket deltas
                              ▼
                     Angular control centre
```

## Running it

```bash
docker compose up -d --build
```

| | |
| --- | --- |
| Control centre | http://localhost:4200 |
| Backend API | http://localhost:18080 |
| Kafka UI | http://localhost:8085 |
| PostgreSQL | `localhost:5433` |

Sign in as any seeded operator. Passwords follow `<role>-dev-password`:

```text
admin@metropulse.test        admin-dev-password
controller@metropulse.test   controller-dev-password
supervisor@metropulse.test   supervisor-dev-password
planner@metropulse.test      planner-dev-password
viewer@metropulse.test       viewer-dev-password
```

A controller can acknowledge alerts and work incidents; a viewer can see everything and change
nothing. These are synthetic demo credentials, documented in the migration that creates them.

### Watching a scenario

The simulator drives the seeded route geometry, and scenarios change how it drives:

```bash
METROPULSE_SIMULATOR_SCENARIO=ROUTE_DEVIATION docker compose up -d simulator --force-recreate
```

`BUNCHING`, `ROUTE_DEVIATION`, `TELEMETRY_LOSS`, `LONG_DWELL`, `EV_LOW_BATTERY`, `MULTI_INCIDENT`,
`RECOVERY`, `NORMAL_OPERATION`. Each is reproducible from its seed.

### Production-style stack

```bash
cp .env.prod.example .env.prod   # then replace every value
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
infra/scripts/smoke-test.sh http://localhost:8080 "$(grep METROPULSE_INGEST_KEY .env.prod | cut -d= -f2)"
```

Only nginx is published. Every secret is required rather than defaulted, so the stack refuses to
start without one.

## Building and testing

```bash
./mvnw test                      # backend unit tests, no infrastructure
./mvnw verify                    # everything; needs PostgreSQL/PostGIS
npm --prefix frontend run test   # Angular unit tests, headless Chrome
npm --prefix frontend run build
```

`mvnw` downloads Maven itself — only Java 21+ is required.

**352 tests** — 316 backend and simulator, 36 frontend. Threshold and rule logic as unit tests;
migrations, PostGIS behaviour, the Kafka consumer, charger concurrency, authentication, schedule
import and the WebSocket as integration tests against real infrastructure. H2 is deliberately not used — it cannot prove any of the PostGIS behaviour the
projection depends on. See [testing.md](docs/testing.md).

## Some things worth looking at

**[The outbox](docs/outbox.md)** — the failure it prevents (commit succeeds, publish fails), and what
it does *not* guarantee: publication is at-least-once, which is why consumers are idempotent.

**[Idempotent consumers](docs/kafka.md)** — every event id is claimed in `processed_event` in the same
transaction as the work it triggers, so redelivery is a no-op rather than a second application.

**[The no-rewind rule](docs/operational-state.md)** — a late event is still valid history, but current
state must not move backwards. One `WHERE` clause on the upsert's conflict branch.

**[Headway and bunching](docs/headway-bunching.md)** — measuring spacing from route progress, why a
stopped vehicle needs a different speed basis, why the route's target headway is not its departure
interval, and why every rule has a persistence window.

**[Schedule adherence](docs/operational-state.md)** — detecting a call at a stop from proximity plus
low speed, why deviation is never interpolated between stops, and why a trip's origin is measured on
departure rather than arrival.

**[Alerts](docs/alerts.md)** — fingerprint deduplication enforced by a partial unique index, and
hysteresis so a vehicle sitting on a threshold cannot flap an alert on and off.

**[Charger concurrency](docs/incidents-and-ev.md)** — why check-then-insert is wrong, what
`SELECT ... FOR UPDATE` fixes, and the negative check: with the lock removed, the test fails.

**[Security](docs/security.md)** — short-lived access tokens, revocable hashed refresh tokens, and two
bugs the tests caught: a revocation rolled back by the exception that triggered it, and a logout that
signed the operator out everywhere.

**[Schedule import](docs/schedule-import.md)** — why an upload stages rather than activates, what a
preview has to say for a planner to decide, why times past midnight are not normalised, and why
`split(",")` is wrong for a stop called "Union Square, North".

**[The outbox under failure](docs/outbox.md)** — telemetry is still accepted while the broker is
unreachable, the backlog drains in order when it returns, and nothing is sent twice.

**[Deployment](docs/deployment.md)** — including the nginx DNS trap that 502s every request after a
backend restart, and how it was verified.

## Layout

```text
backend/     Spring Boot API, projection, rules, alerting
frontend/    Angular control centre
simulator/   Java telemetry simulator
docs/        Architecture, decisions, and what is not built
infra/       nginx edge and operational scripts
```

Backend packages are organised by capability — `telemetry`, `operations`, `alert`, `incident`, `ev`,
`playback`, `analytics`, `auth`, `realtime`, `schedule` — rather than by layer.

The control centre has six screens: **Network** (live map, fleet, headway, alerts), **Incidents**,
**EV**, **Analytics**, **Playback** and **Schedule**.

## What is not built

Written down rather than implied, because a portfolio project that overstates itself is worse than
one with a short honest list:

- **Staged schedule import.** Validation happens in memory and activation is immediate; there is no
  preview a planner can review before switching over.
- **Screenshots and diagrams.** The docs describe the system in prose; there are no images.
- **Component tests for most screens.** The schedule review screen is rendered in tests; the other
  five are not.
- **Retention.** The policy is documented; nothing prunes automatically.
- **Horizontal scale.** One backend instance: two would contend on the outbox publisher and would
  each broadcast to only their own WebSocket subscribers.
- **TLS**, log aggregation, and platform metrics.

Known limitations are listed at the end of each doc, and
[status-and-roadmap.md](docs/status-and-roadmap.md) keeps a list of the defects that a green test
suite missed and only live running caught.

## Stack

Java 21 · Spring Boot 3.5 · Spring Security · Spring Kafka · Spring WebSocket · Flyway ·
PostgreSQL 16 / PostGIS 3.4 · Apache Kafka 4 · Redis 7 · Angular 20 · JUnit 5 · Testcontainers ·
Maven · Docker · nginx · Jenkins
