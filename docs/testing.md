# Testing

## Commands

```bash
./mvnw test
./mvnw verify
npm --prefix frontend run build
```

`mvnw` / `mvnw.cmd` is the Maven Wrapper: it downloads and caches Maven 3.9.9 on first use, so a host
Maven install is not required. Java 21+ is.

## Test types

- Unit tests (JUnit 5): pure rule/threshold logic, for example `ConnectivityStateTest`.
- Integration tests (Spring Boot Test + real PostgreSQL/PostGIS): the full Flyway migration set,
  PostGIS geometry behaviour, `ON CONFLICT` upsert semantics and `jsonb` columns.

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

## Not yet covered

- Kafka consumer and outbox publishing behaviour under broker failure (Kafka Testcontainer).
- Charger reservation concurrency (pessimistic locking).
- API-level tests through MockMvc/`@SpringBootTest` web layer.
- Frontend unit tests.
