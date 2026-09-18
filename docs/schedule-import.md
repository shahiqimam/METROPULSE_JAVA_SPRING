# Schedule Import

```text
POST   /api/v1/admin/schedule/imports              multipart, one part per file — stages only
GET    /api/v1/admin/schedule/imports              what has been staged, newest first
GET    /api/v1/admin/schedule/imports/{id}         one import with its preview
POST   /api/v1/admin/schedule/imports/{id}/activate  put it into service
POST   /api/v1/admin/schedule/imports/{id}/discard   set it aside
```

Split by role rather than by area:

- a **PLANNER** may upload a feed and read what it would change, because that is their job and
  because staging writes nothing operational — a staged feed is a proposal sitting in a table;
- only an **ADMIN** may activate or discard one, because that changes what every operational
  calculation — route progress, headway, deviation, punctuality — is measured against. Not something
  a controller does mid-shift, and not something to do because a file uploaded cleanly.

A CONTROLLER has no business in the schedule area at all, and gets a 403 from every endpoint here.

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

## Four stages, in this order

**Parse.** Text to typed rows. Reports anything unreadable with the file and line number the planner
sees in their spreadsheet.

**Validate.** Asks whether the rows make sense together. Separate from parsing because the failures
differ in kind: a bad number is a typo in one cell, while a trip referencing a missing route means two
files disagree.

**Stage.** The feed is stored with a preview of what it would change. Nothing operational is written.

**Activate.** One transaction. Either the whole schedule moves to its new state or none of it does.

Nothing touches the database until validation has passed, so a rejected feed leaves no trace — not
even a staged record, because a feed nobody can activate is not worth keeping for review. There is a
test for exactly that.

## Why uploading is not activating

Uploading a file used to put it straight into service. That is the wrong shape for this particular
piece of data.

The schedule is the reference every operational number is measured against. Replacing it does not
just change future measurements — it changes what the last hour of them meant, because punctuality
and deviation are comparisons against a timetable that is no longer the one on screen. A change like
that deserves a decision, and a decision needs something to read first.

So an upload produces a **preview**, and someone activates it afterwards.

### What the preview says

Counts alone do not answer the planner's question. "Twelve routes" is the same number whether the
feed adds one to eleven that already exist or rewrites every one of them, so each kind of record is
split into **added** and **updated**, matched on the same natural key the importer writes on —
agencies by name, routes and stops by code, calendars by name, trips by code.

It also names what the feed does *not* mention. Routes and stops the platform already has are left in
place rather than deleted, because vehicles are assigned to routes and history is projected onto
their geometry — so an import that looks like a whole network replacement quietly leaves the old one
beside it. That is worth knowing before approving, not after.

Alongside those are notes: trips with no stop times (nothing on them can be measured for
punctuality), and trips with no shape (their route keeps the geometry vehicles are still projected
onto).

### What the preview does not promise

It is a comparison at a moment. Between staging and activation the schedule can change, so activation
re-parses, re-validates and records **what it actually did** in its own field. Where the preview and
the result disagree, the result is the truth.

### Why the uploaded files are kept, not the parsed feed

Activation re-parses the bytes that were uploaded. Storing the parsed result instead would be faster
and would mean activating something nobody uploaded: a transformation produced by whichever version
of the parser happened to be running at staging time.

An import can be activated or discarded once. A second attempt is refused rather than ignored —
usually it is two people looking at the same screen, and the second needs to be told it has already
happened rather than left believing they did it.

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
