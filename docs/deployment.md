# Deployment

## Two stacks

`docker-compose.yml` is for development: every service publishes a port so you can reach the
database, Kafka UI and the API directly.

`docker-compose.prod.yml` is the production-style stack. The difference is not "more containers" but
**exposure**: only nginx has a published port. PostgreSQL, Kafka, Redis and the backend are reachable
only from inside the Compose network, so the attack surface is one reverse proxy rather than five
services. Kafka UI is absent entirely — it is a development convenience that would hand over topic
contents and cluster administration to anyone who reached it.

```bash
cp .env.prod.example .env.prod   # then replace every value
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
infra/scripts/smoke-test.sh http://localhost:8080 "$(grep METROPULSE_INGEST_KEY .env.prod | cut -d= -f2)"
```

Verified: the stack starts, only nginx is published, and the smoke test passes 17/17 against it.

## Secrets are required, not defaulted

Every secret in the production compose file uses `${VAR:?message}`, so the stack refuses to start
without it. A stack that silently boots with a development signing key is worse than one that will
not boot, because nobody finds out.

The backend enforces the same idea for the JWT secret: shorter than 32 bytes and it refuses to start,
rather than signing tokens with a key weaker than the algorithm's output.

## The nginx DNS trap

This one cost a real debugging session and is worth knowing.

nginx resolves a hostname in a static `upstream` block **once, at startup**, and caches the address
forever. In Docker, restart the backend and it comes back on a different container IP — and nginx
keeps proxying to the dead one. Every request 502s until nginx itself is restarted.

The fix is to name the upstream through a variable, which forces re-resolution per request, with
Docker's embedded DNS as the resolver:

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;

location /api/ {
    set $api_upstream backend:8080;
    proxy_pass http://$api_upstream$request_uri;
}
```

With a variable, nginx does not rewrite the path, so `$request_uri` passes it through unchanged —
which is what is wanted here, since the backend serves those paths directly.

Verified the only way that means anything: the backend was forced onto a new container IP
(`172.28.0.6` → `172.28.0.9`) while nginx was left running, and requests kept succeeding.

There is no `nginx -t` in the image build, because the config names upstreams that exist only once
Compose has created the network — a build-time check fails on name resolution rather than on syntax.

## CI

`Jenkinsfile` runs, in order:

```text
Checkout -> Compile -> Unit tests -> Start PostGIS -> Integration tests
         -> Frontend -> Docker images -> Deploy demo -> Smoke test
```

The order is deliberate: everything cheap runs before anything slow, so a compile error or a broken
rule fails in seconds rather than after containers have been pulled.

Images are tagged with the commit SHA rather than `latest`, because "which build is running" should
have exactly one answer.

The integration stage needs real PostgreSQL/PostGIS — it is started explicitly and torn down in
`post`, so a leftover container cannot make the next build fail on a port clash.

## Health and readiness

```text
GET /api/v1/health          application health, public
GET /actuator/health/**     Spring's probes, public
GET /actuator/**            everything else, ADMIN only
```

nginx also exposes `GET /health` for a load balancer, which proxies to the application health
endpoint without exposing the rest of Actuator.

**Degradation is deliberate.** Health does not fail when Kafka is unreachable, because ingest does
not depend on the broker: telemetry still commits, outbox rows queue up, and the publisher drains
them when Kafka returns. Failing health there would take a working ingest path out of service.

What does suffer while Kafka is down is the projection — current state stops advancing and the
dashboard shows telemetry ageing. That is visible in the UI rather than hidden.

## Running the tests

```bash
./mvnw test      # unit tests
./mvnw verify    # everything, needs PostgreSQL/PostGIS
npm --prefix frontend run build
```

See [testing.md](testing.md) for how integration tests obtain a database.

## Data and retention

Both volumes (`postgres`, `kafka`) persist across restarts in the production stack. Nothing is pruned
automatically yet — the retention policy in the project brief (30 days of raw telemetry, 90 days of
derived history, alerts and incidents kept) is **documented but not implemented**. A scheduled
cleanup that deletes operational history is not something to add before the policy is agreed.

## Not built

- No TLS. The edge speaks HTTP; a real deployment terminates TLS at nginx or in front of it.
- Single instance of everything. Two backends would contend on the outbox publisher (needs
  `FOR UPDATE SKIP LOCKED`) and would each broadcast to only their own WebSocket subscribers (needs a
  broker relay).
- No log aggregation, metrics scraping or alerting on the platform itself.
- The Jenkinsfile has not been run on a real Jenkins instance; the stages were validated by running
  each command by hand.
