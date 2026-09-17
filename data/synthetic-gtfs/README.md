# Synthetic GTFS feed

A small, valid, entirely fictional feed for exercising the importer.

```bash
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"email":"admin@metropulse.test","password":"admin-dev-password"}' \
  http://localhost:18080/api/v1/auth/login | python -c "import json,sys; print(json.load(sys.stdin)['accessToken'])")

curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  $(for f in data/synthetic-gtfs/*.txt; do echo -n " -F files=@$f"; done) \
  http://localhost:18080/api/v1/admin/schedule/import
```

Import is restricted to administrators: replacing the schedule changes what every operational
calculation is measured against.

`Union Square, North` contains a comma on purpose — it is the case a naive `split(",")` gets wrong.
