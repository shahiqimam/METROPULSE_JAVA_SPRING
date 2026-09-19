# 0007 - JDBC over JPA

## Context

The project brief lists JPA/Hibernate, and for most of the build the starter was on the classpath
with `ddl-auto: validate` configured. Nothing ever used it: there is not one `@Entity` in the
codebase, and every query is written with `JdbcTemplate`.

That is the worst of the two options. It neither uses the tool nor states a decision, and it leaves
an interviewer with a reasonable question — "why is Hibernate here?" — that the repository cannot
answer.

## Decision

Persistence is JDBC via `JdbcTemplate`. The JPA starter and the Hibernate JSON-type helper beside it
are removed, along with the dead `spring.jpa` configuration.

## Why

The work that actually matters in this system is SQL that an ORM would hide rather than help with:

- **The no-rewind rule** is a `WHERE` clause on an upsert's conflict branch. Through JPA it becomes a
  read, a comparison in Java, and a write — which is a race rather than a rule.
- **PostGIS** does the geometry: `ST_LineLocatePoint` for route progress, `ST_Distance` on
  `geography` for deviation in metres, `ST_DWithin` for stop arrivals. Hibernate Spatial can map
  these, but the projection would still be written as SQL and then wrapped.
- **Charger reservation** is `SELECT ... FOR UPDATE` inside one transaction, and its test proves the
  lock matters by removing it. Pessimistic locking through JPA is possible and less legible.
- **Alert deduplication** is a partial unique index the database enforces. The rule lives in the
  schema, not in an entity's equals method.
- **Reads are projections**, not aggregates: the control centre reads one flat row per vehicle
  assembled across five tables. Mapping that to entities and back to a DTO is work for no gain.

There is no object graph here to navigate, no lazy-loading to benefit from, and no aggregate whose
invariants are enforced in Java rather than in the schema. What JPA is good at, this system does not
need; what this system needs, JPA gets in the way of.

## Alternatives

- **Keep the starter and use JPA for one aggregate**, such as incidents, which is the most
  object-like part of the domain. Rejected: two persistence styles in one codebase is a worse thing
  to explain than one deliberate choice, and the incident transition table is already a single
  well-tested switch.
- **Keep the starter unused.** Rejected: an unused dependency is a claim the code does not support.
- **jOOQ.** A better fit than JPA for this shape of work, and rejected only because `JdbcTemplate`
  already does it with nothing to generate and nothing else to learn.

## Consequences

- Transactions come from `DataSourceTransactionManager` via `spring-boot-starter-jdbc`;
  `@Transactional` behaves as before.
- Schema changes are Flyway migrations and nothing validates entities against them, so a column
  rename breaks at the query that uses it. Integration tests run the full migration set against real
  PostgreSQL, which is what catches it.
- The brief's JPA checkbox is not met. That is a deliberate and documented answer rather than an
  omission — the honest version of the alternative, which was a dependency nobody used.
