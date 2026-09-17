INSERT INTO agency (name, timezone)
VALUES ('MetroPulse Transit Authority', 'America/New_York')
ON CONFLICT (name) DO NOTHING;

INSERT INTO route (agency_id, code, short_name, long_name, geometry)
SELECT
    agency.id,
    'M42',
    'M42 Crosstown',
    'MetroPulse 42nd Street Crosstown',
    ST_SetSRID(ST_MakeLine(ARRAY[
        ST_MakePoint(-74.0060, 40.7128),
        ST_MakePoint(-74.0020, 40.7140),
        ST_MakePoint(-73.9980, 40.7152),
        ST_MakePoint(-73.9945, 40.7163),
        ST_MakePoint(-73.9900, 40.7178)
    ]), 4326)
FROM agency
WHERE agency.name = 'MetroPulse Transit Authority'
ON CONFLICT (code) DO NOTHING;

INSERT INTO stop (code, name, location)
VALUES
    ('M42-001', 'West Terminal', ST_SetSRID(ST_MakePoint(-74.0060, 40.7128), 4326)),
    ('M42-002', 'Hudson Exchange', ST_SetSRID(ST_MakePoint(-74.0020, 40.7140), 4326)),
    ('M42-003', 'Central Library', ST_SetSRID(ST_MakePoint(-73.9980, 40.7152), 4326)),
    ('M42-004', 'Civic Plaza', ST_SetSRID(ST_MakePoint(-73.9945, 40.7163), 4326)),
    ('M42-005', 'East Terminal', ST_SetSRID(ST_MakePoint(-73.9900, 40.7178), 4326))
ON CONFLICT (code) DO NOTHING;

INSERT INTO service_calendar (
    name,
    start_date,
    end_date,
    monday,
    tuesday,
    wednesday,
    thursday,
    friday,
    saturday,
    sunday
)
VALUES (
    'Weekday Base Service 2026',
    DATE '2026-01-01',
    DATE '2026-12-31',
    TRUE,
    TRUE,
    TRUE,
    TRUE,
    TRUE,
    FALSE,
    FALSE
)
ON CONFLICT (name) DO NOTHING;

INSERT INTO trip (
    route_id,
    service_calendar_id,
    trip_code,
    direction,
    headsign,
    planned_start_seconds,
    planned_end_seconds
)
SELECT
    route.id,
    service_calendar.id,
    'M42-WKD-0700-EAST',
    'EASTBOUND',
    'East Terminal',
    25200,
    27000
FROM route
CROSS JOIN service_calendar
WHERE route.code = 'M42'
  AND service_calendar.name = 'Weekday Base Service 2026'
ON CONFLICT (trip_code) DO NOTHING;

INSERT INTO stop_time (
    trip_id,
    stop_id,
    stop_sequence,
    planned_arrival_seconds,
    planned_departure_seconds
)
SELECT trip.id, stop.id, seed.stop_sequence, seed.arrival_seconds, seed.departure_seconds
FROM trip
JOIN (
    VALUES
        ('M42-001', 1, 25200, 25230),
        ('M42-002', 2, 25620, 25650),
        ('M42-003', 3, 26040, 26070),
        ('M42-004', 4, 26460, 26490),
        ('M42-005', 5, 27000, 27000)
) AS seed(stop_code, stop_sequence, arrival_seconds, departure_seconds) ON TRUE
JOIN stop ON stop.code = seed.stop_code
WHERE trip.trip_code = 'M42-WKD-0700-EAST'
ON CONFLICT (trip_id, stop_sequence) DO NOTHING;
