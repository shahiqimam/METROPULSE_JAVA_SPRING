# Demo walkthrough

Twenty minutes, start to finish. Every command and every figure below was run against the stack
rather than written from memory; where a number appears it is one this produced, and yours will
differ in the details because the fleet is moving.

The point of the walkthrough is not that the screens render. It is that **nothing here is staged**.
The simulator drives vehicles and the backend works out, on its own, that they are bunched, off
route, silent or late. No scenario tells it the answer.

## 1. Start it

```bash
docker compose up -d --build
```

Wait for the API:

```bash
until curl -sf http://localhost:18080/api/v1/health; do sleep 5; done
```

Then open http://localhost:4200 and sign in as `controller@metropulse.test` with
`controller-dev-password`. Other roles follow the same pattern — `admin@`, `planner@`, `supervisor@`,
`viewer@`, each with `<role>-dev-password`. All synthetic, seeded by a migration, useless anywhere
else.

Keep a token for the API parts. These examples format JSON with `python`, which the tooling here
already depends on, rather than `jq`, which it does not:

```bash
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"email":"controller@metropulse.test","password":"controller-dev-password"}' \
  http://localhost:18080/api/v1/auth/login \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
```

## 2. Watch a service that is running properly

Give it two or three minutes. On **Network** you will see four vehicles working their way along the
M42, each showing its next stop, and a schedule standing that mostly reads "on time".

That is worth a moment's attention, because it is doing more than it looks. Vehicles run scheduled
trips: each takes a departure, drives between stops at the speed the timetable implies, stands still
for thirty seconds at each one, reaches the far terminal and waits out its layover before taking
another. The backend measures all of it independently.

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/routes/M42/headway \
  | python -c "
import json, sys
d = json.load(sys.stdin)
print('target', d['targetHeadwaySeconds'], 's')
for p in d['pairs']:
    print(' ', p['followerVehicleId'], '->', p['leaderVehicleId'],
          round(p['gapMeters']), 'm', round(p['headwaySeconds'] or 0), 's', p['classification'])
"
```

A settled fleet reads 76–91 seconds against an 83-second target, all NOMINAL. The target is derived
from the timetable, and the measurement comes from positions the simulator never labelled — the two
agreeing is the closest thing here to an independent check.

Then **Analytics**, which should show punctuality at or near 100% on time with an average deviation
of a few seconds. Those come from `stop_arrival` rows written when a vehicle was detected within 40 m
of a stop at walking pace: actual against planned, never interpolated.

## 3. Break it

Scenarios are a single environment variable, and switching one restarts only the simulator:

```bash
METROPULSE_SIMULATOR_SCENARIO=BUNCHING docker compose up -d simulator
```

`NORMAL_OPERATION`, `BUNCHING`, `ROUTE_DEVIATION`, `TELEMETRY_LOSS`, `LONG_DWELL`, `EV_LOW_BATTERY`,
`MULTI_INCIDENT`, `RECOVERY`.

### BUNCHING

One vehicle crawls at 35% speed. Nothing tells the backend this. Within a minute or two the headway
call above reads:

```text
target 83 s
  BUS-042 -> BUS-317  12 m  4 s BUNCHING
  BUS-317 -> BUS-101   2 m  0 s BUNCHING
  BUS-101 -> BUS-204   1 m  0 s BUNCHING
```

The whole line has piled up behind the slow one, which is what severe bunching actually looks like.
The vehicles are not allowed to drive through each other: a follower that catches its leader queues
behind it, and that is what makes bunching last rather than flicker.

A condition appears first as *watching*, and becomes an alert only once it has held for the
90-second persistence window:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/routes/M42/headway \
  | python -c "
import json, sys
for c in json.load(sys.stdin)['conditions']:
    print(c['type'], c['followerVehicleId'], '->', c['leaderVehicleId'],
          'confirmed' if c['confirmed'] else 'watching', round(c['observedForSeconds']), 's')
"
```

Then:

```bash
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:18080/api/v1/alerts?includeClosed=false' \
  | python -c "
import json, sys
for a in json.load(sys.stdin):
    print(a['type'], a['severity'], a['status'], a['vehicleId'], '|', a['details'])
"
```

```text
BUNCHING MINOR OPEN BUS-317 | {'ratioToTarget': 0.04, 'headwaySeconds': 3.37,
 'leaderVehicleId': 'BUS-101', 'followerVehicleId': 'BUS-317', 'targetHeadwaySeconds': 83}
```

Three and a half seconds apart against a target of eighty-three. The alert carries the measurement
that caused it, not just a label.

### TELEMETRY_LOSS

```bash
METROPULSE_SIMULATOR_SCENARIO=TELEMETRY_LOSS docker compose up -d simulator
```

One vehicle stops reporting. Watch it go ONLINE → STALE → OFFLINE on the Network screen as its
telemetry ages past 15 and then 60 seconds, with a `TELEMETRY_OFFLINE` alert opening behind it.
Connectivity is derived at read time from the age of the last observation and never stored — a
stored value would itself go stale.

The vehicle comes back after a while, the alert enters recovery, and closes as RECOVERED once the
condition has stayed away. It does not close the instant the condition stops.

### ROUTE_DEVIATION

```bash
METROPULSE_SIMULATOR_SCENARIO=ROUTE_DEVIATION docker compose up -d simulator
```

One vehicle is placed 180 m to the side of the route shape. PostGIS measures the distance from the
geometry in metres and the backend reports roughly 179.7 — the difference is the simulator's
flat-earth approximation against real geodesic distance, and the backend's number is the right one.

## 4. Work an alert, then an incident

On **Network**, acknowledge the alert: it records who saw it and stays open. Closing it by hand is
also recorded, and a second close returns 409 rather than silently succeeding.

Alerts are noticed by rules. Incidents are opened by people, and have a workflow:

```bash
ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"SERVICE_DISRUPTION","severity":"MAJOR","title":"M42 bunched behind BUS-042","routeCode":"M42"}' \
  http://localhost:18080/api/v1/incidents \
  | python -c "import json,sys; print(json.load(sys.stdin)['id'])")

status() { python -c "import json,sys; print(json.load(sys.stdin)['status'])"; }

curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/incidents/$ID/acknowledge | status
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/incidents/$ID/mitigate   | status
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:18080/api/v1/incidents/$ID/cancel
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:18080/api/v1/incidents/$ID/resolve   | status
```

```text
ACKNOWLEDGED
MITIGATING
409          <- cancelling something already being worked on
RESOLVED
```

That 409 is the point. Work has begun, so the incident is resolved, not cancelled — and the rule
lives in one transition table on the server, not in the buttons. The **Incidents** screen offers only
the transitions that table allows, which is a convenience built on the rule rather than a second copy
of it.

## 5. Two controllers, one charger

```bash
claim() {
  curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"chargerCode\":\"CHG-E01\",\"vehicleId\":\"$1\"}" \
    http://localhost:18080/api/v1/charging-sessions \
    | python -c "
import json, sys
d = json.load(sys.stdin)
print(d.get('code') or d.get('status'), '|', d.get('chargerCode') or d.get('message'))
"
}

claim BUS-042
claim BUS-101
```

```text
ACTIVE | CHG-E01
CHARGER_NOT_AVAILABLE | Charger CHG-E01 is not available: OCCUPIED.
```

The charger is claimed under `SELECT ... FOR UPDATE`, with a unique partial index as the database's
own statement of the rule. `ChargingConcurrencyIntegrationTest` runs the race properly with
concurrent threads, and fails when the lock is removed — that negative check is what makes the test
worth anything.

## 6. Replay the last quarter of an hour

On **Playback**, pick a vehicle, set a 15-minute window and play it back at 5×.

The thing to watch is the **Network** screen afterwards: it is unchanged. Replay reads immutable
history, writes nothing operational, and produces no events for consumers. A replayed frame carries a
position and no schedule comparison, because an hour-old position measured against today's timetable
would be a different measurement wearing the same name.

## 7. Change the timetable, carefully

Sign in as `planner@metropulse.test`, open **Schedule**, and upload the seven files in
`data/synthetic-gtfs/`.

Nothing happens to the service. You get a preview: what would be added, what would be written over,
and — the part worth reading — what the feed does *not* mention. Existing routes and stops are left
in place rather than deleted, because vehicles are assigned to routes and history is projected onto
their geometry, so an import that looks like a whole network replacement quietly leaves the old one
beside it.

The planner cannot activate it. Sign in as `admin@metropulse.test` to put it into service; the record
then shows who uploaded it, who approved it, and what activation actually did — which is not
necessarily what the preview expected.

## 8. Put it back

```bash
METROPULSE_SIMULATOR_SCENARIO=NORMAL_OPERATION docker compose up -d simulator
```

Alerts close themselves as their conditions recover. For a clean slate:

```bash
docker compose down -v && docker compose up -d --build
```

## What to look at in the code afterwards

- [outbox.md](outbox.md) — the failure it prevents, and what it does not guarantee
- [operational-state.md](operational-state.md) — the no-rewind rule and stop-arrival detection
- [headway-bunching.md](headway-bunching.md) — why a stopped vehicle needs a different speed basis
- [status-and-roadmap.md](status-and-roadmap.md) — including the defects a green test suite missed
  and only live running caught
