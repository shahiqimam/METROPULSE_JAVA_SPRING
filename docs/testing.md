# Testing

## Commands

```bash
./mvnw test                      # backend unit tests
./mvnw verify                    # everything; needs PostgreSQL/PostGIS
npm --prefix frontend run test   # Angular unit tests, headless Chrome
npm --prefix frontend run build
```

`mvnw` / `mvnw.cmd` is the Maven Wrapper: it downloads and caches Maven 3.9.9 on first use, so a host
Maven install is not required. Java 21+ is.

## Test types

- Unit tests (JUnit 5): pure rule/threshold logic, for example `ConnectivityStateTest`.
- Integration tests (Spring Boot Test + real PostgreSQL/PostGIS): the full Flyway migration set,
  PostGIS geometry behaviour, `ON CONFLICT` upsert semantics and `jsonb` columns.
- Broker tests (Spring Kafka's in-process broker): the operational-state listener consuming real
  records, idempotent redelivery, and dead-lettering. These need no Docker.
- Frontend unit tests (Karma + Jasmine, headless Chrome): the status rules a controller reads, the
  session service, and the interceptor's single refresh-and-retry on a 401.

H2 is deliberately not used. It cannot prove any of the PostGIS behaviour the projection depends on.

## How integration tests get a database

`PostgisIntegrationTest` starts a `postgis/postgis:16-3.4` Testcontainers container by default.

If `METROPULSE_TEST_DB_URL` is set, the tests use that PostGIS database instead of starting a
container. This exists because docker-java (used by Testcontainers) cannot negotiate an API version
with every Docker Engine build; on such a host the container path fails before any test runs while
the Docker CLI itself works fine.

To run against the Compose stack's PostGIS on such a host:

```bash
docker compose up -d postgres
docker compose exec -T postgres psql -U metropulse -d postgres -c "DROP DATABASE IF EXISTS metropulse_test;" -c "CREATE DATABASE metropulse_test OWNER metropulse;"
METROPULSE_TEST_DB_URL=jdbc:postgresql://localhost:5433/metropulse_test \
METROPULSE_TEST_DB_USERNAME=metropulse \
METROPULSE_TEST_DB_PASSWORD=metropulse \
./mvnw test
```

The database is migrated by Flyway on context start and each test class clears the telemetry tables
it writes to, so the same database can be reused between runs.

## A note on jasmine versions

The Angular 20 test builder pins to jasmine 5. Installing jasmine 7 makes zone.js fail with
`Cannot assign to read only property 'describe'` before a single test runs — worth knowing, because
the error names neither package.

## Not yet covered

- Outbox publishing under broker failure: the documented scenario where Kafka is down, telemetry
  still commits, the outbox row stays unpublished, and the publisher drains it once Kafka returns.
- Charger reservation concurrency (pessimistic locking).
- API-level tests through MockMvc/`@SpringBootTest` web layer.
