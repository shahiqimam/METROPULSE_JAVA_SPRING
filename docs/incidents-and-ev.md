# Incidents and EV Operations

## Incidents

An alert is raised by a rule and closes when the rule stops firing. An incident is opened by a
controller, moves under their control, and never closes itself.

They are deliberately separate. Conflating them gives you either rules that cannot be dismissed, or
operational work that vanishes because a measurement changed.

### The workflow

```text
OPEN ──► ACKNOWLEDGED ──► MITIGATING ──► RESOLVED
  │            │                            ▲
  │            └────────────────────────────┤
  └──► CANCELLED ◄──────────────────────────┘
```

- `OPEN` may go anywhere.
- `ACKNOWLEDGED` may mitigate, resolve or cancel.
- `MITIGATING` may only resolve. Work has already been done, so the incident ends rather than being
  written off.
- `RESOLVED` and `CANCELLED` are terminal. Nothing reopens: reopening would lose the distinction
  between one long incident and two related ones, and the timeline would stop being a reliable
  account of what happened.

The table lives in `IncidentStatus.allowedTransitions()` and nowhere else, so there is one answer to
"can this incident go there".

### Why there is no status field on the API

```text
POST /api/v1/incidents/{id}/acknowledge
POST /api/v1/incidents/{id}/mitigate
POST /api/v1/incidents/{id}/resolve
POST /api/v1/incidents/{id}/cancel
POST /api/v1/incidents/{id}/notes
```

Each action is its own endpoint. A `PUT` with a status field would let a stale screen or a typo put
an incident anywhere, which defeats the point of having a workflow at all. A refused transition is a
409 naming both states, and leaves no timeline entry.

### The timeline

Every transition and note is appended to `incident_timeline` with its actor and timestamp, in the
same transaction as the state change. An incident whose state changed without a record of who
changed it is worse than one that did not change, because it still looks authoritative.

Notes can be added to a resolved incident — writing down what was learned afterwards is normal — and
they are timestamped, so they cannot be mistaken for something said at the time.

Who acted comes from the authenticated principal, never from the request body.

## EV operations

Depots hold chargers; chargers hold one active session at a time.

```text
GET  /api/v1/chargers
GET  /api/v1/charging-sessions?activeOnly=true
POST /api/v1/charging-sessions              { chargerCode, vehicleId }
POST /api/v1/charging-sessions/{id}/complete
```

### The concurrency problem, and the lock

Two controllers sending vehicles to the last free charger is not an unlikely race; it is what happens
at shift change. The obvious implementation is wrong:

```text
read charger status  -> AVAILABLE        read charger status  -> AVAILABLE
insert session                           insert session
mark charger OCCUPIED                    mark charger OCCUPIED
```

Both read before either wrote. So the reservation takes a row lock first:

```sql
SELECT id, code, status FROM charger WHERE code = ? FOR UPDATE
```

The second transaction blocks there until the first commits, then reads `OCCUPIED` and is refused
with `409 CHARGER_NOT_AVAILABLE`. READ_COMMITTED is enough: the lock, not the isolation level, is
what serialises them.

Two unique partial indexes back this up as the database's own statement of the rule:

```sql
CREATE UNIQUE INDEX uq_charging_session_active_charger
    ON charging_session (charger_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_charging_session_active_vehicle
    ON charging_session (vehicle_id) WHERE status = 'ACTIVE';
```

The lock exists to produce a good error; the constraints exist so the rule holds even if a future
code path forgets to lock. The vehicle constraint also covers a race the charger lock cannot: one
vehicle sent to two *different* chargers at once.

### How the test was checked

`ChargingConcurrencyIntegrationTest` runs real transactions on separate threads, released together by
a latch, against real PostgreSQL. It cannot be written with mocks — what is under test is whether the
database serialises the transactions.

It was verified the only way that means anything: **with `FOR UPDATE` removed, the test fails.** A
concurrency test that still passes with the protection removed is not testing the protection.

## Not built

- Charging sessions do not appear on the dashboard yet; the API is there, the UI is not.
- No charger reservation ahead of time, only "plug in now".
- Battery readings on a session come from the vehicle's current state at the moment it starts and
  ends, so a session whose vehicle stopped reporting has gaps.
- Incidents are not linked to the alerts that prompted them.
