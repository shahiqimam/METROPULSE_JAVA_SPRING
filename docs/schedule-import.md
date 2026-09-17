# Schedule Import

```text
POST /api/v1/admin/schedule/import      multipart, one part per file, ADMIN only
```

Administrator-only, because replacing the schedule changes what every operational calculation — route
progress, headway, deviation — is measured against. That is not something a controller does mid-shift.

## The supported subset

```text
agency.txt      required
routes.txt      required
stops.txt       required
calendar.txt    required
trips.txt       required
stop_times.txt  required
shapes.txt      optional, but a new route cannot be created without one
```

A bounded subset of GTFS, not the whole specification. Frequencies, transfers, fare rules, calendar
exceptions and parent stations are not read.

## Three stages, in this order

**Parse.** Text to typed rows. Reports anything unreadable with the file and line number the planner
sees in their spreadsheet.

**Validate.** Asks whether the rows make sense together. Separate from parsing because the failures
differ in kind: a bad number is a typo in one cell, while a trip referencing a missing route means two
files disagree.

**Activate.** One transaction. Either the whole schedule moves to its new state or none of it does.

Nothing touches the database until validation has passed, so a rejected feed leaves no trace — there
is a test for exactly that.

## What is checked

- Every required file is present, and every required column within it
- Numbers, dates (`yyyyMMdd`) and times (`HH:MM:SS`) parse
- Ids are unique within their file
- Coordinates are inside valid ranges, and not `0,0` — which is in the Atlantic and almost always a
  missing value rather than a stop
- Calendars end after they start and run on at least one day
- Every reference resolves: trip → route, trip → service, stop time → trip, stop time → stop, trip →
  shape
- Every trip has at least two calls, with unique sequences, departures not before arrivals, and times
  that never go backwards along the trip
- Shapes have at least two points

Every problem found is reported together. Returning one error per attempt turns a ten-minute fix into
ten round trips.

## Times past midnight

`25:10:00` is **not** an error and is not normalised to `01:10`. A transit service day runs past
midnight: that trip departs at 01:10 the following calendar morning but belongs to the previous
service day. Normalising it would move the trip to the start of its own day and make every schedule
comparison wrong.

Times are stored as seconds since the start of the service day, so `25:10:00` is 90,600.

## Why Commons CSV rather than splitting on commas

`Union Square, North` is an ordinary stop name. A naive `split(",")` silently shifts every column
after it, and the failure surfaces much later as a coordinate that makes no sense. The synthetic feed
in `data/synthetic-gtfs/` includes that name deliberately, and a test asserts it survives.

## Upsert, not delete-and-recreate

Routes, stops, calendars and trips are matched on their feed ids and updated in place. Deleting and
recreating would be simpler to write and would break everything pointing at them: vehicles are
assigned to routes, alerts and incidents reference them, and telemetry history is projected onto
their geometry. Re-importing must not orphan a month of operational history.

Stop times are the exception — a trip's calls are replaced wholesale, because a changed pattern has no
row-by-row correspondence with the old one.

## Known limitations

- **Shapes belong to trips in GTFS, but MetroPulse projects onto a route's geometry.** The first shape
  among a route's trips is used. That is right when a route's trips share a path and approximate when
  they do not; a route with genuinely different patterns per direction needs per-pattern geometry.
- Files are read into memory, with a 16 MB cap. A real feed would stream to disk.
- There is no staging table and no preview: validation happens in memory and activation is immediate.
  A larger system would stage a version and let a planner review it before switching over.
- No `calendar_dates.txt`, so holiday exceptions cannot be expressed.
- Nothing removes routes or stops that disappear from a later feed; they remain until deleted by hand.

## Trying it

`data/synthetic-gtfs/` holds a small valid feed. See its README for the upload command.

Verified live: the feed imports (1 route, 4 stops, 2 trips, 8 stop times, 4 shape points), the
comma-containing stop name survives, a feed with an out-of-range latitude and a `0,0` stop is
rejected with both problems listed, and a controller attempting the import gets 403.
