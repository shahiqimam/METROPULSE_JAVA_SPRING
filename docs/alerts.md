# Alerts

An alert is a condition a controller is asked to look at. The engine's whole job is deciding which
true things are worth saying, and for how long — because an alert stream nobody trusts is worse than
no alerts at all.

## Signals, candidates, alerts

```text
rules          -> signals     what is true right now
engine         -> candidates  conditions that have not lasted long enough yet
engine         -> alerts      conditions that have
```

`AlertRules` is pure: state in, signals out, no database and no clock. `AlertEngine` owns everything
time-dependent.

## Deduplication

Every signal carries a **fingerprint**: what makes this the same condition across evaluations.

```text
ROUTE_DEVIATION|BUS-042                 one vehicle, one problem
BUNCHING|M42|BUS-042|BUS-101            a pair; a different leader is a different problem
```

One live alert per fingerprint, enforced by a partial unique index rather than by checking first and
inserting after — two evaluations racing would both pass a check:

```sql
CREATE UNIQUE INDEX uq_alert_live_fingerprint
    ON alert (fingerprint)
    WHERE status <> 'CLOSED';
```

Closed alerts are excluded, so the same condition can recur later as a new alert with its own
history rather than reopening an old one.

## Persistence and recovery

Each type carries two windows:

| Type | Opens after | Closes after absent for | Severity |
| --- | --- | --- | --- |
| `TELEMETRY_OFFLINE` | immediately | 15 s | MAJOR |
| `ROUTE_DEVIATION` | 60 s | 60 s | MAJOR |
| `BUNCHING` | immediately | 60 s | MINOR |
| `EXCESSIVE_GAP` | immediately | 60 s | MINOR |
| `LOW_BATTERY` | 60 s | 120 s | MAJOR |
| `OVER_CAPACITY` | 60 s | 60 s | MINOR |
| `VEHICLE_LATE` | 60 s | 120 s | MINOR |
| `VEHICLE_EARLY` | 60 s | 120 s | MINOR |
| `LONG_DWELL` | immediately | 60 s | MINOR |

"Immediately" means the condition already carries its own wait: a vehicle is only OFFLINE after 60
seconds of silence, a headway condition is only confirmed after 90, and a long dwell is itself a
measurement of elapsed time. Requiring another window would double-count it.

A condition that vanishes before maturing leaves no trace — the controller never sees it. A live
alert whose condition stops is **not** closed at once: it enters recovery, and closes only if the
condition stays away for the recovery window. If it returns first, recovery is cancelled and it
stays the same alert.

`opened_at` is when the condition started, not when the engine noticed it had lasted, because
"how long has this been going on" is the question a controller actually asks.

## Hysteresis

Opening and clearing use different thresholds, so a measurement sitting on a boundary cannot flap:

```text
ROUTE_DEVIATION   opens above 100 m, clears below 60 m
LOW_BATTERY       opens at or under 20%, clears above 30%
OVER_CAPACITY     opens at 100% of capacity, clears below 90%
VEHICLE_LATE      opens at 5 minutes late, clears below 3
VEHICLE_EARLY     opens at 90 seconds early, clears below 45
LONG_DWELL        opens at 3 minutes standing, clears below 2
```

The late and early bands are deliberately asymmetric: five minutes late against ninety seconds early.
A passenger who arrives on time for a bus that has already gone waits a full headway, while a late
bus still turns up. Treating the two symmetrically would mean either tolerating early running that
strands people or alerting constantly on lateness nobody minds.

Rules are told which fingerprints already have a live alert so they can apply the wider band.

## Lateness and dwell are different problems

A vehicle three minutes late somewhere along its route is running badly. A vehicle that has been
standing at a stop for three minutes is blocking it. They need different responses from a controller,
so they are different alert types rather than one "delayed" type with a reason field.

Both depend on stop arrivals being recorded: schedule deviation comes from the last call a vehicle
made, and a dwell is an arrival that has not been closed by a departure. Neither can fire for a
vehicle that does not report which trip it is running.

A vehicle that goes silent while standing at a stop raises `TELEMETRY_OFFLINE`, not `LONG_DWELL`.
Nothing has been heard from it; the stored dwell says where it was, not that it is still there.

## What a controller can do

```text
GET  /api/v1/alerts?includeClosed=false
GET  /api/v1/alerts/summary
GET  /api/v1/alerts/{id}
POST /api/v1/alerts/{id}/acknowledge
POST /api/v1/alerts/{id}/close
```

**Acknowledge** records who saw it. It does not close it: the condition is still true, and the engine
keeps it alive until it recovers. Acknowledging twice keeps the first acknowledgement, because who
saw it first is the useful fact. The acknowledging controller comes from the authenticated principal,
never from the request body.

**Close** is a controller saying "I have dealt with this", not "this can never happen again" — so if
the condition persists, the engine will legitimately raise it again as a new alert. Acting on an
already-closed alert is a 409 rather than a silent no-op, because it almost always means the screen
was stale.

Status is never set directly through the API. Every transition goes through the service, which is
what makes "closed" always carry a reason.

## Not built

Four alert types in the project brief are missing, and all four depend on work that does not exist
yet rather than on the engine:

- `VEHICLE_LATE` and `VEHICLE_EARLY` need schedule deviation, which needs the simulator to run
  scheduled trips rather than a continuous loop.
- `LONG_DWELL` needs stop-arrival detection.
- `TELEMETRY_STALE` is deliberately not an alert: STALE is within the range of ordinary network
  behaviour, and alerting on it would mean alerting constantly. It is shown in the UI instead.

Also missing: no alert history retention policy, and alerts are not published to Kafka or pushed over
WebSocket — the dashboard polls.
