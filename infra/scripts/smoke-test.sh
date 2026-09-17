#!/usr/bin/env bash
#
# Smoke test: does a deployed stack actually work?
#
# Not a substitute for the test suite. This answers a narrower question - is the thing that was just
# deployed wired up - by exercising one path through every layer: nginx, the API, authentication,
# the database, and the projection that telemetry feeds.
#
# Usage: infra/scripts/smoke-test.sh [base-url] [ingest-key]

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
INGEST_KEY="${2:-${METROPULSE_INGEST_KEY:-dev-ingest-key}}"
EMAIL="${METROPULSE_SMOKE_EMAIL:-controller@metropulse.test}"
PASSWORD="${METROPULSE_SMOKE_PASSWORD:-controller-dev-password}"

passed=0
failed=0

check() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "  PASS  $name"
    passed=$((passed + 1))
  else
    echo "  FAIL  $name"
    failed=$((failed + 1))
  fi
}

expect_status() {
  local expected="$1"
  local actual
  shift
  actual=$(curl -s -o /dev/null -w '%{http_code}' "$@")
  [ "$actual" = "$expected" ]
}

echo "MetroPulse smoke test against ${BASE_URL}"
echo

echo "Edge and health"
check "the app is served"            expect_status 200 "${BASE_URL}/"
check "health responds"              expect_status 200 "${BASE_URL}/api/v1/health"

echo
echo "Authentication"
check "reading without a token is refused" expect_status 401 "${BASE_URL}/api/v1/alerts"
check "bad credentials are refused"  expect_status 401 -X POST \
  -H 'Content-Type: application/json' \
  -d '{"email":"nobody@metropulse.test","password":"wrong"}' \
  "${BASE_URL}/api/v1/auth/login"

TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}" \
  "${BASE_URL}/api/v1/auth/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

if [ -z "${TOKEN}" ]; then
  echo "  FAIL  login returned no access token"
  echo
  echo "Cannot continue without a token."
  exit 1
fi
echo "  PASS  login returns an access token"
passed=$((passed + 1))

AUTH=(-H "Authorization: Bearer ${TOKEN}")

echo
echo "Read APIs"
check "routes"        expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/routes"
check "vehicle state" expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/telemetry/vehicles/latest"
check "alerts"        expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/alerts"
check "incidents"     expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/incidents"
check "chargers"      expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/chargers"
check "analytics"     expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/analytics/ev"
check "headway"       expect_status 200 "${AUTH[@]}" "${BASE_URL}/api/v1/routes/M42/headway"

echo
echo "Telemetry round trip"
EVENT_ID="smoke-$(date +%s)-$$"
check "ingest accepts a telemetry event" expect_status 202 -X POST \
  -H 'Content-Type: application/json' \
  -H "X-Ingest-Key: ${INGEST_KEY}" \
  -d "{\"sourceEventId\":\"${EVENT_ID}\",\"vehicleId\":\"BUS-042\",\"recordedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"latitude\":40.7152,\"longitude\":-73.9980,\"speedKph\":24.0,\"headingDegrees\":90.0,\"occupancyEstimate\":20,\"batteryPercent\":75}" \
  "${BASE_URL}/api/v1/telemetry/ingest"

check "the same event is not accepted twice" bash -c "
  curl -s -X POST -H 'Content-Type: application/json' -H 'X-Ingest-Key: ${INGEST_KEY}' \
    -d '{\"sourceEventId\":\"${EVENT_ID}\",\"vehicleId\":\"BUS-042\",\"recordedAt\":\"2026-01-01T00:00:00Z\",\"latitude\":40.7152,\"longitude\":-73.9980,\"speedKph\":24.0,\"headingDegrees\":90.0,\"occupancyEstimate\":20,\"batteryPercent\":75}' \
    '${BASE_URL}/api/v1/telemetry/ingest' | grep -q DUPLICATE"

check "ingest refuses the wrong key" expect_status 401 -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Ingest-Key: definitely-not-the-key' \
  -d '{"sourceEventId":"smoke-bad-key","vehicleId":"BUS-042","recordedAt":"2026-01-01T00:00:00Z","latitude":40.7152,"longitude":-73.9980,"speedKph":24.0,"headingDegrees":90.0,"occupancyEstimate":20,"batteryPercent":75}' \
  "${BASE_URL}/api/v1/telemetry/ingest"

# The event has to travel through the outbox, Kafka and the consumer before it becomes state, so
# give it a few seconds rather than asserting immediately.
#
# What is asserted is that the projection is keeping up, not that this particular event is current.
# On a stack with the simulator running, the smoke event is superseded within seconds by a newer one
# - which is the no-rewind rule working correctly, not a failure.
echo "  ....  waiting for the projection to catch up"
projected=1
for _ in $(seq 1 15); do
  # telemetryAgeSeconds is a float, so take the whole-second part.
  age=$(curl -s "${AUTH[@]}" "${BASE_URL}/api/v1/telemetry/vehicles/latest"     | grep -o '"telemetryAgeSeconds":[0-9]*' | head -1 | cut -d: -f2)
  if [ -n "${age}" ] && [ "${age}" -lt 120 ]; then
    projected=0
    break
  fi
  sleep 2
done

if [ "${projected}" -eq 0 ]; then
  echo "  PASS  current vehicle state is being projected and is fresh"
  passed=$((passed + 1))
else
  echo "  FAIL  current vehicle state is missing or stale"
  failed=$((failed + 1))
fi

echo
echo "Authorization"
VIEWER_TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"email":"viewer@metropulse.test","password":"viewer-dev-password"}' \
  "${BASE_URL}/api/v1/auth/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

if [ -n "${VIEWER_TOKEN}" ]; then
  check "a viewer cannot open an incident" expect_status 403 -X POST \
    -H "Authorization: Bearer ${VIEWER_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"type":"OTHER","severity":"MINOR","title":"smoke"}' \
    "${BASE_URL}/api/v1/incidents"
fi

echo
echo "${passed} passed, ${failed} failed"
[ "${failed}" -eq 0 ]
