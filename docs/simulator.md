# Simulator

The simulator is a separate Spring Boot application that posts synthetic telemetry to
`POST /api/v1/telemetry/ingest`. It is the only source of vehicle data in MetroPulse; there is no
real operator feed and there never will be.

## How vehicles move

Vehicles drive along a route shape, not around a decorative loop of coordinates.

- `RoutePath` holds the polyline and converts a normalised position (0.0 at the start, 1.0 at the
  end) into a latitude/longitude, plus the heading of the segment the vehicle is on.
- `FleetSimulator` advances each vehicle by *distance* every tick (`speed × tick interval`), divides
  that by the shape length, and wraps at 1.0 so the vehicle keeps running the route.
- Vehicles start evenly spaced around the shape, so relative spacing is a consequence of how fast
  each one is driven. That is what makes bunching and gaps observable: the simulator never declares
  two vehicles bunched, it just makes one crawl and lets the backend measure the result.

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
| `NORMAL_OPERATION` | Every vehicle cruises on the shape, evenly spaced. |
| `BUNCHING` | The leader crawls at 35% speed, so the vehicle behind closes the gap. |
| `ROUTE_DEVIATION` | One vehicle is placed 180 m to the right of the shape, past the 100 m threshold. |
| `TELEMETRY_LOSS` | One vehicle stops reporting for 45 ticks, long enough to go OFFLINE, then returns. |
| `LONG_DWELL` | One vehicle holds at zero speed while the rest keep moving. |
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
