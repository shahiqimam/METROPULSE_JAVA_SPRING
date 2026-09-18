# Playback and Analytics

## Playback

```text
POST /api/v1/playback/sessions        { vehicleId?, routeCode?, from, to, speed? }
GET  /api/v1/playback/sessions/{id}
GET  /api/v1/playback/sessions/{id}/frames?offset=0&limit=500
```

A session records what was asked for and how many frames fall in the window, so a client can size its
scrubber before fetching anything and then page through by id.

### Playback is read-only, and that is the design

Nothing in playback writes to `vehicle_current_state`, publishes to Kafka, or feeds the alert engine.

That matters more than it sounds. Replaying last Tuesday must not make the control centre believe a
bus is where it was last Tuesday, and replaying a bunching incident must not raise the bunching alert
a second time. Two tests assert exactly this: current state is unchanged after a replay, and a replay
produces no events for consumers to process.

Frames are read from `vehicle_telemetry` rather than copied into the session. Duplicating history
creates a second version of the past that can drift from the first.

### Bounds

- A window cannot exceed 24 hours.
- A page cannot exceed 1000 frames; larger requests are clamped, not honoured.

Without these, "replay everything for every vehicle" is a way to read the whole telemetry table
through one GET.

## Analytics

```text
GET /api/v1/analytics/punctuality?windowHours=24
GET /api/v1/analytics/service-regularity?windowHours=24
GET /api/v1/analytics/alerts?windowHours=24
GET /api/v1/analytics/incidents?windowHours=24
GET /api/v1/analytics/ev?windowHours=24
```

Every endpoint takes a window, because a metric without a period is not a metric.

### Punctuality and regularity are different questions

They are reported separately, under their own names, rather than blended into one score.

**Punctuality** asks whether the service ran to its timetable: recorded stop arrivals, actual against
planned. **Regularity** asks whether it ran evenly spaced, from headway conditions. A frequent
service can be perfectly regular and consistently late, or punctual on average while bunching badly,
so a single combined number would answer neither question.

For most of this project's life there was no punctuality endpoint at all, and the reason is worth
keeping: comparing actual against scheduled arrivals needs actual arrivals, and until stop-arrival
detection existed there was nothing to compare. Any figure derived from what was available — route
progress against elapsed time, say — would have been a different measurement wearing a word that
means something specific in transit. The endpoint appeared when the data behind it did, not before.

The on-time window is 90 seconds early to 5 minutes late. Asymmetric, because passengers experience
the two differently: a late bus still turns up, an early one has gone.

### What each endpoint measures

- **punctuality** — per route: calls measured, how many were on time, late and early, the share on
  time, average deviation, and the worst in each direction. A call that was never detected is not
  counted, which understates how many calls were made rather than overstating how punctual they
  were.
- **service-regularity** — per route: target headway, conditions live now, and bunching/gap alerts
  raised in the window with their average duration. A route with nothing wrong still appears, because
  "M42 ran within target all day" is the useful thing to be able to say.
- **alerts** — counts by type. Counting alerts rather than evaluations is the point: the engine
  evaluates constantly and deduplicates, so an alert count is a count of distinct problems.
- **incidents** — volume by outcome, plus average time to acknowledge and to resolve, measured from
  the incidents' own timestamps. That describes what controllers did, not what a rule believed.
- **ev** — charger availability, sessions in the window, average session length and charge gained,
  and fleet battery state.

Charger utilisation is reported as the share occupied *now*, not averaged over the window: sessions
are not sampled often enough for a time-weighted figure to mean anything yet.
