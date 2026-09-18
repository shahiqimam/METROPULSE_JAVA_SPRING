# MetroPulse Architecture

MetroPulse is a modular monolith. Capability packages — `telemetry`, `operations`, `alert`,
`incident`, `ev`, `playback`, `analytics`, `auth`, `realtime`, `schedule` — own their API,
application services, domain logic, persistence and tests. Packages are organised by what they are
about, not by which layer they sit in, so a change to how headway works is one directory rather than
four.

## Runtime

```mermaid
flowchart LR
    SIM[Java simulator<br/>scheduled trips] -->|POST /telemetry/ingest| API
    UI[Angular control centre] -->|REST| API
    UI <-->|STOMP over WebSocket| API

    subgraph Backend [Spring Boot]
        API[API + application services]
        PROJ[Operational-state consumer]
        RULES[Headway / alert evaluators]
    end

    API -->|observation + event,<br/>one transaction| PG[(PostgreSQL 16<br/>+ PostGIS)]
    API -->|outbox publisher| K[[Kafka<br/>metropulse.telemetry.v1]]
    K --> PROJ
    PROJ --> PG
    RULES --> PG
    RULES -->|broadcasts| UI

    NGINX[nginx edge] -.->|only published port<br/>in the production stack| API
```

Redis is in both Compose stacks and used by nothing. It was provisioned for caching and rule
counters that PostgreSQL has handled adequately, and saying so is better than adding a decorative
cache.

## How one observation becomes state

The path that matters most, because everything operational is derived from it.

```mermaid
sequenceDiagram
    participant S as Simulator
    participant A as Ingest API
    participant DB as PostgreSQL
    participant P as Outbox publisher
    participant K as Kafka
    participant C as State consumer

    S->>A: POST /telemetry/ingest
    A->>DB: INSERT vehicle_telemetry + outbox_event
    Note over A,DB: One transaction. The event cannot be<br/>lost by a broker that is down, because<br/>it is not sent from inside the request.
    A-->>S: 202 Accepted

    P->>DB: SELECT unpublished
    P->>K: publish, keyed by vehicle
    P->>DB: mark published

    K->>C: deliver (at least once)
    C->>DB: claim event id in processed_event
    Note over C,DB: Claimed in the same transaction as<br/>the work, so redelivery is a no-op.
    C->>DB: upsert vehicle_current_state
    Note over C,DB: Guarded: a late event is kept as<br/>history but never moves current<br/>state backwards.
```

Three properties hold this together, and each has its own tests:

- **The outbox.** The observation and the event commit together, so the failure where a request
  succeeds and its event vanishes cannot happen.
- **At-least-once delivery, idempotent consumers.** The event id is claimed in `processed_event` in
  the same transaction as the projection, so Kafka redelivering is a no-op rather than a second
  application.
- **No rewind.** An event that arrives late is stored as history, but the upsert refuses to move
  current state backwards. One `WHERE` clause on the conflict branch.

## What is derived, and from what

```mermaid
flowchart TD
    OBS[vehicle_telemetry<br/>immutable history] --> STATE[vehicle_current_state<br/>one row per vehicle]
    OBS --> PB[Playback<br/>replays history, writes nothing]

    STATE -->|ST_LineLocatePoint| PROG[Route progress]
    STATE -->|ST_Distance on geography| DEV[Route deviation in metres]
    STATE -->|telemetry age| CONN[ONLINE / STALE / OFFLINE]

    SCHED[(Schedule:<br/>trips + stop times)] --> ARR
    STATE -->|within 40 m, under 10 km/h| ARR[stop_arrival<br/>actual vs planned]
    ARR --> ADH[Schedule deviation]
    ARR --> PUNC[Punctuality]
    ARR --> DWELL[Dwell]

    PROG --> HW[Headway between<br/>consecutive vehicles]
    HW --> COND[Bunching / excessive gap<br/>with persistence windows]

    DEV --> AL[Alert engine]
    CONN --> AL
    ADH --> AL
    DWELL --> AL
    COND --> AL
    AL --> INC[Incidents<br/>opened by a controller]
```

The schedule is the reference every one of these comparisons is made against, which is why replacing
it is a reviewed decision rather than a side effect of an upload.

## Staged schedule import

```mermaid
stateDiagram-v2
    [*] --> Rejected: parse or validation fails
    [*] --> STAGED: parsed, validated, preview computed
    STAGED --> ACTIVATED: an administrator approves
    STAGED --> DISCARDED: set aside
    ACTIVATED --> [*]
    DISCARDED --> [*]
    Rejected --> [*]

    note right of Rejected
        Nothing written, not even a
        staged record: a feed nobody
        can activate is not worth
        keeping for review.
    end note

    note right of STAGED
        Uploaded files are kept, not the
        parsed feed. Activation re-parses
        and re-validates them.
    end note
```

## Alert lifecycle

```mermaid
stateDiagram-v2
    [*] --> Candidate: a rule signals
    Candidate --> [*]: condition vanishes before maturing
    Candidate --> OPEN: held for its persistence window
    OPEN --> ACKNOWLEDGED: a controller sees it
    OPEN --> Recovering: condition stops
    ACKNOWLEDGED --> Recovering: condition stops
    Recovering --> OPEN: condition returns
    Recovering --> CLOSED: stayed away for the recovery window
    ACKNOWLEDGED --> CLOSED: closed by hand
    CLOSED --> [*]
```

`opened_at` records when the condition started, not when the engine noticed it had lasted, because
"how long has this been going on" is the question a controller actually asks.

## Deployment

```mermaid
flowchart LR
    subgraph Production stack
        direction LR
        N[nginx<br/>the only published port] --> F[frontend]
        N --> B[backend]
        B --> PG[(postgres)]
        B --> K[[kafka]]
        B --> R[(redis)]
        SIM[simulator] --> B
    end

    U((Operator)) -->|HTTP| N
```

PostgreSQL, Kafka, Redis and the backend are reachable only from inside the Compose network, so the
attack surface is one reverse proxy rather than five services. Every secret is required rather than
defaulted: a stack that silently starts with a development signing key is worse than one that
refuses to start.

## Where the detail is

| Question | Document |
|---|---|
| Why an outbox, and what it does not guarantee | [outbox.md](outbox.md) |
| Idempotent consumption, poison messages, dead-lettering | [kafka.md](kafka.md) |
| Projection, no-rewind, PostGIS, schedule adherence | [operational-state.md](operational-state.md) |
| Headway estimation and its speed basis | [headway-bunching.md](headway-bunching.md) |
| Deduplication, persistence windows, hysteresis | [alerts.md](alerts.md) |
| Incident workflow and charger concurrency | [incidents-and-ev.md](incidents-and-ev.md) |
| Replay and metrics | [playback-and-analytics.md](playback-and-analytics.md) |
| Staging, previewing and activating a feed | [schedule-import.md](schedule-import.md) |
| Tokens, roles, and two bugs the tests caught | [security.md](security.md) |
| What is built, verified, and not built | [status-and-roadmap.md](status-and-roadmap.md) |
