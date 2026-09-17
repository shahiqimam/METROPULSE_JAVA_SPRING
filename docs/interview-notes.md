# Interview Notes

Questions this project actually equips you to answer, with the answer grounded in something you can
point at. Nothing here is a definition to memorise: each one names the decision, the alternative, and
what went wrong when it was got wrong.

---

## "What happens if the database commit succeeds and the Kafka publish fails?"

That is the failure the outbox exists for, and it is worth saying plainly that a database and a
broker are two systems — there is no single transaction across both.

Ingest writes the observation and an outbox row in one database transaction. A separate publisher
does the risky part later. If Kafka is down, rows stay unpublished with `attempt_count` incremented
and `last_error` recorded; the API keeps accepting telemetry throughout, because accepting telemetry
never depended on the broker.

**What it does not guarantee:** single publication. A publisher that sends successfully and dies
before marking the row published will send again. That is deliberate — making publication
exactly-once costs far more than making consumers idempotent.

→ `docs/outbox.md`

## "So how do you avoid processing an event twice?"

The consumer claims the event id in `processed_event` **in the same transaction as its own writes**.
A unique violation means it has already been applied, so it returns without doing anything. Because
the claim and the work commit together, a failure rolls back both, and the redelivery is applied once
in effect.

That is at-least-once delivery plus an idempotent consumer — not exactly-once, and worth not claiming
otherwise.

## "Late data arrives. What happens to current state?"

A late event is still valid history, so it is stored. But current state must not rewind, or a bus
jumps backwards on the screen.

One `WHERE` clause on the upsert's conflict branch:

```sql
WHERE vehicle_current_state.recorded_at < EXCLUDED.recorded_at
   OR (vehicle_current_state.recorded_at = EXCLUDED.recorded_at
       AND vehicle_current_state.received_at <= EXCLUDED.received_at)
```

Both timestamps are stored because they answer different questions: when the vehicle observed it, and
when the platform accepted it. Ties on the first are broken by the second.

## "Two controllers claim the last charger at the same time. What happens?"

This is not a rare race; it is what happens at shift change.

The obvious implementation is wrong: read status → AVAILABLE, insert session, mark occupied. Both
transactions read AVAILABLE before either writes.

So the reservation takes a row lock first — `SELECT ... FROM charger WHERE code = ? FOR UPDATE`. The
second transaction blocks there, then reads OCCUPIED and gets a clean `409 CHARGER_NOT_AVAILABLE`.
Two unique partial indexes on active sessions back it up as the database's own statement of the rule,
and cover a race the lock cannot: one vehicle sent to two *different* chargers.

**The part worth volunteering:** the test was checked by removing `FOR UPDATE` and confirming it
fails. A concurrency test that still passes with the protection removed is not testing the
protection.

## "Why partition Kafka by vehicle id?"

Ordering is guaranteed only within a partition. Keying by vehicle keeps one vehicle's events ordered
relative to each other, which is all the projection needs — state is per vehicle, and ordering across
vehicles is meaningless.

It also caps useful parallelism: three partitions means at most three consumers in the group do work.

## "What do you do with a message you can never process?"

Distinguish transient from permanent. A database blip is worth retrying; a malformed envelope or an
unknown vehicle is not, so those exception types are registered as non-retryable and go straight to
`metropulse.telemetry.v1.DLT`.

The reason this matters is the partition: one bad event retried forever stalls every vehicle whose
events hash to it, not just the vehicle in the bad event.

## "Why is the projection behind a consumer instead of in the ingest transaction?"

It started in the ingest transaction — simple and strongly consistent. Moving it behind the consumer
means derived work does not grow on the request path, and state is rebuilt from the same events any
other consumer sees.

The cost is honest and worth stating: current state is now **eventually** consistent. There is a
window after ingest returns 202 where the observation exists and state has not caught up.

→ ADR 0004

## "Show me somewhere Spring's proxy semantics bit you."

Two places, both real.

A `@KafkaListener` calling a `@Transactional` method **on itself** bypasses the proxy entirely, so the
idempotency claim would have committed independently of the projection write. The listener and the
transactional handler are separate beans for that reason.

Worse: refresh-token reuse detection revoked the token family and then threw — in the same
transaction, so the exception rolled the revocation back. The thief was turned away once and the
family stayed valid. `RefreshTokenRevoker` now commits in `REQUIRES_NEW`.

## "How do you keep alerting from becoming noise?"

Three mechanisms, and the reason is the same each time: an alert stream nobody trusts is worse than
no alerts.

- **Fingerprints.** One live alert per fingerprint, enforced by a partial unique index rather than
  check-then-insert, which two evaluations racing would both pass.
- **Persistence windows.** A condition must hold before it counts. One that vanishes early leaves no
  trace in the controller's history at all.
- **Hysteresis.** Route deviation opens at 100 m and clears at 60 m. Without the band, a vehicle
  sitting at 100 m opens and closes an alert on alternate readings.

Recovery is the mirror: a live alert whose condition stops is not closed at once, it enters recovery
and closes only if the condition stays away.

## "Why PostGIS rather than storing latitude and longitude?"

Because the questions are spatial. Route progress is `ST_LineLocatePoint` against the stored
LineString; deviation is `ST_Distance` on the `geography` type, which returns meters rather than
degrees. Doing that in application code means reimplementing projection maths and keeping it
consistent with whatever the map draws.

The map draws the same stored geometry the projection measures against — so a vehicle drawn off the
line really is off the line.

**Verification worth quoting:** the simulator asks for a 180 m lateral offset using flat local maths;
PostGIS, on the other side of a broker, measures 179.66 m.

## "What is headway, and what is bunching?"

Headway is the time gap between consecutive vehicles. Bunching is when it collapses — vehicles
arrive together, then nothing for a long time — and it is the failure passengers actually experience
on a frequent service, regardless of whether the timetable was met.

Thresholds are ratios of the route's own target, so a 30-second route and a 30-minute route are
judged on the same terms.

**The subtlety worth raising:** headway is `gap / speed`, which breaks exactly where it matters. A
vehicle queued twelve meters behind another is barely moving, so dividing by its own speed reports a
huge headway — or nothing — for a pair that is plainly bunched. Below a speed floor the route's
median moving speed is used instead, and every reading reports which basis it used.

## "Why is there no punctuality endpoint?"

Because punctuality means actual arrivals against scheduled ones, and nothing here detects arrivals
at stops — so there is no actual to compare. A number derived from route progress against elapsed
time would be a different measurement wearing a word that means something specific in transit.

Regularity is reported instead, under its own name.

This is the answer to give when asked what you left out: the gap is named, the reason is specific,
and the thing that would unblock it is known (stop-arrival detection, which needs trip-aware
simulation).

## "How do you authenticate the WebSocket?"

Not the way the HTTP API is authenticated, and that is the trap. A browser **cannot set an
`Authorization` header on a WebSocket handshake**, so the security rules permit `/ws` and the token
travels in the STOMP `CONNECT` frame, where an interceptor verifies it.

Letting the handshake through is not letting the connection through. Without the interceptor an
unauthenticated client could open a socket and receive every broadcast — a leak no amount of REST
authorisation would catch, because that data never travels over REST.

## "Why REST and WebSocket rather than just WebSocket?"

REST is the baseline; the socket carries changes. A client that has only ever seen deltas cannot know
what it missed while disconnected — and it will be disconnected.

That makes the socket *allowed* to drop messages: nothing is acknowledged or replayed, and the worst
case is a few seconds of staleness. Polling stays as a fallback, so a proxy that will not upgrade
degrades the experience rather than breaking it.

## "Why a modular monolith rather than microservices?"

The capabilities share one transactional database and one team. Splitting them would replace a
`@Transactional` method with a distributed transaction, and replace a join with a network call, in
exchange for independent deployability nobody needs at this size.

The boundaries are drawn anyway — packages by capability, not by layer — so the seams exist if they
are ever needed.

## "What breaks at a hundred times the event rate?"

The order it breaks in, roughly:

1. The outbox publisher is single-instance and polls; two would contend on the same rows. Needs
   `FOR UPDATE SKIP LOCKED`.
2. Three partitions caps consumer parallelism at three.
3. The projection is one upsert per event — fine until the write rate exceeds what one primary
   sustains.
4. `vehicle_telemetry` grows without limit; retention is documented, not implemented.
5. The WebSocket broker is in-memory, so a second instance broadcasts only to its own subscribers.

**Not claimed:** any throughput number. Nothing was load tested, so there is nothing to quote.

## "What would you do next?"

Trip-aware simulation, because it unblocks the largest genuine gap: stop-arrival detection, schedule
deviation, punctuality, and the three alert types that depend on them.

---

## Things to be careful not to overclaim

- **Not** exactly-once. At-least-once delivery with idempotent consumers.
- **Not** load tested. No throughput or latency figures are published because none were measured.
- **Not** a real ITS. Every input is synthetic, and the thresholds are project values chosen to make
  scenarios legible, not transit-industry standards.
- **Testcontainers** is the default path for integration tests, but on the development machine
  docker-java could not negotiate an API version with Docker Engine 29, so the suite was run against
  a real PostGIS database supplied through `METROPULSE_TEST_DB_URL`. Real PostGIS behaviour was
  exercised; the container-start path itself was not.
