-- A full day of service on the seeded M42 route, timed to the route the vehicles actually drive.
--
-- The single 07:00 trip was enough to prove the schedule model, but not to measure adherence: a
-- vehicle running at any other time had no trip to be measured against.
--
-- The timings here are derived from the seeded shape rather than chosen. That shape is 1,459 metres
-- long, so the original 30-minute pattern implied an average of under 3 km/h - and a simulator made
-- to run to it would have had to crawl, which would have made every speed, headway and punctuality
-- figure downstream describe a network that could not exist. The pattern below runs the same five
-- stops in six and a half minutes: 270 seconds of driving, which is 19 km/h, plus a 30-second dwell
-- at the origin and at each of the three intermediate stops. Arrival offsets follow each stop's own
-- distance along the shape, so a vehicle driving at one steady speed is on time at every one of them
-- rather than only at the ends.
--
-- Times are service-day seconds. 05:00 is 18000; the last departure at 23:00 is 82800.

-- Two minutes between departures. Four vehicles cover the 390-second trip with a 90-second layover
-- each, which is what lets the fleet run continuously without a fifth vehicle appearing from nowhere.
--
-- The target headway is not that two minutes, and the difference is worth being explicit about. The
-- headway calculator measures a pair as the time the follower would need to cover the gap at the
-- speed it is driving, which excludes the dwells it will actually stop for on the way. Two vehicles
-- running two minutes apart to this timetable are 449 m apart, and 449 m at the pattern's driving
-- speed of 5.4 m/s reads as 83 seconds. Comparing a measurement that ignores dwell against a target
-- that includes it would report every correctly-spaced service on the route as bunched.
UPDATE route SET target_headway_seconds = 83 WHERE code = 'M42';

-- The trip seeded before this pattern existed is re-timed onto it. Leaving it on its old 30-minute
-- window would give one trip of the day a timetable no vehicle could keep.
UPDATE trip
SET planned_end_seconds = planned_start_seconds + 390
WHERE trip_code = 'M42-WKD-0700-EAST';

UPDATE stop_time
SET planned_arrival_seconds = trip.planned_start_seconds + pattern.arrival_offset,
    planned_departure_seconds = trip.planned_start_seconds + pattern.departure_offset
FROM trip,
     (VALUES
         (1, 0, 30),
         (2, 97, 127),
         (3, 194, 224),
         (4, 283, 313),
         (5, 390, 390)
     ) AS pattern(stop_sequence, arrival_offset, departure_offset)
WHERE stop_time.trip_id = trip.id
  AND trip.trip_code = 'M42-WKD-0700-EAST'
  AND stop_time.stop_sequence = pattern.stop_sequence;

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
    calendar.id,
    -- The code carries the departure time, so an operator reading a trip code knows when it runs.
    'M42-WKD-' || to_char((departure_seconds || ' seconds')::interval, 'HH24MI') || '-EAST',
    'EASTBOUND',
    'East Terminal',
    departure_seconds,
    -- The pattern below takes 390 seconds end to end; the trip's window has to match it, or the
    -- summary would disagree with the stop times it summarises.
    departure_seconds + 390
FROM route
CROSS JOIN service_calendar AS calendar
CROSS JOIN generate_series(18000, 82800, 120) AS departure_seconds
WHERE route.code = 'M42'
  AND calendar.name = 'Weekday Base Service 2026'
ON CONFLICT (trip_code) DO NOTHING;

-- Stop times for every generated trip, relative to that trip's own departure.
INSERT INTO stop_time (
    trip_id,
    stop_id,
    stop_sequence,
    planned_arrival_seconds,
    planned_departure_seconds
)
SELECT
    trip.id,
    stop.id,
    pattern.stop_sequence,
    trip.planned_start_seconds + pattern.arrival_offset,
    trip.planned_start_seconds + pattern.departure_offset
FROM trip
JOIN route ON route.id = trip.route_id
JOIN (
    VALUES
        ('M42-001', 1, 0, 30),
        ('M42-002', 2, 97, 127),
        ('M42-003', 3, 194, 224),
        ('M42-004', 4, 283, 313),
        ('M42-005', 5, 390, 390)
) AS pattern(stop_code, stop_sequence, arrival_offset, departure_offset) ON TRUE
JOIN stop ON stop.code = pattern.stop_code
WHERE route.code = 'M42'
  AND trip.trip_code LIKE 'M42-WKD-%-EAST'
ON CONFLICT (trip_id, stop_sequence) DO NOTHING;
