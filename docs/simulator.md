# Simulator

The simulator is a separate Spring Boot application that posts synthetic telemetry to
`POST /api/v1/telemetry/ingest`. It is the only source of vehicle data in MetroPulse; there is no
real operator feed and there never will be.

## How vehicles move

Vehicles run scheduled trips along a route shape. They do not circle it, and they do not drive at a
speed of their own choosing.

- `RoutePath` holds the polyline and converts a normalised position (0.0 at the start, 1.0 at the
  end) into a latitude/longitude, plus the heading of the segment the vehicle is on.
- `TripSchedule` gives each vehicle a departure and keeps it there for the whole trip, then hands it
  the fleet's next one round — a block, as an operator would run it. Four vehicles on a two-minute
  headway each get every fourth departure, which leaves a 90-second layover at the terminal.
- `TripPattern` says where the trip should be at any second of its run: a staircase, not a ramp. The
  vehicle drives between stops and stands still at them, and the stops are not evenly spaced along
  the shape.
- `FleetSimulator` moves each vehicle towards that scheduled position, at up to 1.8× the scheduled
  speed. It can press on to make up a delay but not teleport, and where the schedule holds still it
  holds still too.

Spacing is therefore a consequence of the timetable being run, or not being run. The simulator never
declares two vehicles bunched or one of them late; it makes one crawl, and the backend measures what
that did to the service.

### Speed is derived, not chosen

Driving speed is the shape's length over the driving time the timetable allows — for the seeded M42,
1,459 m over 270 seconds, or about 19 km/h.

This matters more than it sounds. The seeded timetable originally allowed thirty minutes for that
1,459 m shape, which is under 3 km/h: nothing had ever had to run it, so nothing had ever disagreed
with it. A simulator made to keep that timetable would have had to crawl, and every speed, headway
and punctuality figure downstream would then have described a network that could not exist. A
simulator that ignored it instead would have arrived minutes early at every stop, and punctuality
would have been measuring the disagreement rather than the service. The seed was re-timed to the
geometry it describes (`V16`), and the simulator derives its speed from that.

### Standing at stops

A vehicle that never stops is never observed calling anywhere. Stop-arrival detection asks for a
vehicle within 40 m of a stop at or below walking pace, so a fleet that cruises past its stops
produces a timetable nothing is ever measured against — which is exactly what happened the first
time this was run end to end. Dwelling is not bolted on for that purpose: it falls out of following a
timetable that stands still for 30 seconds at each intermediate stop.

The default shape is the seeded M42 geometry from the backend's `V4` migration. **It has to match
the seeded route geometry**, because the backend projects every observation onto that geometry with
PostGIS. A simulator path that wanders off the seeded shape reports route deviation that no scenario
asked for. Override it with `metropulse.simulator.route-points` (a list of `"latitude,longitude"`).

Distance maths in the simulator uses a local equirectangular approximation. Over a few kilometres of
city street the error is well under a meter; the backend still measures deviation properly with
PostGIS `geography`. A requested 180 m offset comes back from the backend as roughly 179.7 m.

## Scenarios

Set with `METROPULSE_SIMULATOR_SCENARIO` (Compose passes it through to the container).

| Scenario | What it does |
| --- | --- |
| `NORMAL_OPERATION` | Every vehicle runs its trip to the timetable. |
| `BUNCHING` | The leader crawls at 35% speed, so the vehicle behind closes the gap. A vehicle a scenario is holding back does not get to chase the timetable — letting it would cancel the fault. |
| `ROUTE_DEVIATION` | One vehicle is placed 180 m to the right of the shape, past the 100 m threshold. |
| `TELEMETRY_LOSS` | One vehicle stops reporting for 45 ticks, long enough to go OFFLINE, then returns. |
| `LONG_DWELL` | One vehicle holds at zero speed while the rest keep moving, past the 180 s threshold. |
| `EV_LOW_BATTERY` | One vehicle starts at 14% battery and keeps draining with distance. |
| `MULTI_INCIDENT` | Deviation, dwell and telemetry loss on three different vehicles at once. |
| `RECOVERY` | A degraded phase at 30% speed, then a return to normal, for hysteresis rules. |

Thresholds referenced here (bunching, 100 m off route, 60 s to OFFLINE) are MetroPulse project
thresholds, not transit-industry standards.

## Determinism

Speed jitter and occupancy come from a `Random` seeded with `metropulse.simulator.seed`. The same
seed, fleet, scenario and tick interval reproduce the same run, which `FleetSimulatorTest` asserts.

`sourceEventId` embeds a per-process run id, so restarting the simulator does not collide with the
telemetry it already sent; re-sending an id is still a no-op at the ingest API.

## Configuration

| Property | Environment variable | Default |
| --- | --- | --- |
| `metropulse.simulator.ingest-url` | `METROPULSE_SIMULATOR_INGEST_URL` | `http://localhost:8080/api/v1/telemetry/ingest` |
| `metropulse.simulator.ingest-key` | `METROPULSE_SIMULATOR_INGEST_KEY` | `dev-ingest-key` |
| `metropulse.simulator.scenario` | `METROPULSE_SIMULATOR_SCENARIO` | `NORMAL_OPERATION` |
| `metropulse.simulator.seed` | `METROPULSE_SIMULATOR_SEED` | `42` |
| `metropulse.simulator.interval` | `METROPULSE_SIMULATOR_INTERVAL` | `PT2S` |
| `metropulse.simulator.vehicle-ids` | — | the four seeded development buses |
| `metropulse.simulator.route-points` | — | the seeded M42 shape |

The vehicle ids must exist in the backend's `vehicle` table, or ingest rejects the events as unknown
vehicles.

## Tests

- `RoutePathTest` — endpoints, clamping, length, heading, and that a requested lateral offset really
  moves the point by that many meters.
- `FleetSimulatorTest` — every scenario, plus even initial spacing, movement along the shape, and
  reproducibility from a fixed seed.
