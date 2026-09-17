# Headway and Bunching

Headway is how far apart, in time, consecutive vehicles on a route are running. It is what
passengers actually experience: a route advertised "every 10 minutes" that runs two buses together
and then nothing for twenty has kept its timetable on paper and failed in practice.

## Calculating it

Vehicles on a route are ordered by route progress — the normalised position PostGIS gives when their
observed point is projected onto the route shape. Each vehicle's leader is the one ahead of it, and
its headway is the time to cover the gap between them:

```text
gap along the shape (meters) / a speed (m/s)
```

The route is treated as a loop: the last vehicle's leader is the first, wrapping past the end of the
shape. That suits the simulator, which runs the shape continuously. A scheduled service running
discrete trips would want the open-ended form, where the vehicle in front has no follower.

**This is an estimate.** It assumes distance along the shape is distance travelled, and that the
speed used holds for the whole gap. It is not the same thing as measuring the time between two
vehicles passing the same stop, which is how headway is usually observed in the field.

### Which speed, and why it matters

Normally the follower's own speed is used.

That breaks down exactly where it matters most. A vehicle queued directly behind another is barely
moving, so dividing by its own speed reports an enormous headway — or nothing at all — for a pair
that is plainly bunched. Two vehicles twelve meters apart are bunched whatever the follower's
speedometer says.

So when the follower is at or below a speed floor (3 kph), the route's **reference speed** is used
instead: the median speed of the vehicles on that route that are actually moving. The pair is still
measured in time, which keeps it comparable with the route's target headway, and the basis is
reported alongside the number:

```text
FOLLOWER_SPEED          the follower's own speed
ROUTE_REFERENCE_SPEED   the route's median moving speed, because the follower is stopped
UNKNOWN                 nothing on the route is moving, so there is no headway to state
```

An estimate is never passed off as a direct observation.

## The rules

Thresholds are ratios of the route's own `target_headway_seconds`, so a route running every 30
seconds and one running every 30 minutes are judged on the same terms:

```text
BUNCHING        headway below 40% of target
EXCESSIVE_GAP   headway above 180% of target
```

**These are MetroPulse project thresholds, not transit-industry standards.**

### Persistence

Neither counts until it has held for **90 seconds**.

Spacing fluctuates constantly — one traffic light is enough to move it. A rule without a persistence
window opens and closes conditions continuously, which is how alerting becomes noise nobody reads. A
condition is therefore recorded as soon as it is seen, but marked `confirmed` only once the spell has
lasted the window. The UI shows the difference: *Watching* versus *Sustained*.

A consequence worth knowing: confirmation needs an observation at the start of the spell and another
at least 90 seconds later. Time passing before a condition was ever observed proves nothing about it.

### Recovery

When a pair's spacing returns to range, its condition row is deleted. If the pair deteriorates again
later it starts a fresh window rather than resuming the old one — a pair that has recovered has to
earn its confirmation again.

### Vehicles that are not reporting

A vehicle whose telemetry is older than 15 seconds is excluded from the calculation entirely. Its
last known position is not evidence of where it is now, and treating it as such would invent bunching
out of a vehicle that simply stopped reporting.

## Where it runs

`HeadwayEvaluator` runs on a timer (10 s by default), not per telemetry event. Headway is a property
of a whole route at an instant, so re-evaluating it for every vehicle's event would repeat the same
work with mostly the same answer. The tick only has to be short relative to the persistence window.

The clock is injected, so tests move time instead of waiting for it.

## Reading it

```text
GET /api/v1/routes/{code}/headway      pairs, classifications and conditions for one route
GET /api/v1/routes/headway/conditions  conditions across every route
```

Pairs are computed on read, because they describe the present and go stale the moment a vehicle
moves. Conditions are read from `headway_condition`, because they carry the history the rules depend
on: when the spell started, and whether it has lasted.

## Seeing it happen

```bash
METROPULSE_SIMULATOR_SCENARIO=BUNCHING docker compose up -d simulator --force-recreate
```

One vehicle is driven at 35% speed. Because simulated vehicles queue rather than passing through each
other, the vehicle behind it closes up and stays there — and the pack that forms shows up as
`BUNCHING` pairs behind the slow vehicle and an `EXCESSIVE_GAP` in front of it, which is what
bunching looks like in reality: vehicles together, then a hole.

## Not yet built

- Conditions do not become `Alert` records with acknowledge and close workflow; that is the alerting
  slice.
- No headway at a stop: the measure is vehicle-to-vehicle along the shape, not observed at a point.
- Direction is not considered. The seeded route has one direction; a real route would need headway
  calculated per direction, since opposing vehicles are not each other's leaders.
- No schedule comparison. Headway says how evenly the service is spread, not whether it is on time.
