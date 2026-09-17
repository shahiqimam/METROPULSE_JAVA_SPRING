package com.metropulse.schedule.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Imports a validated feed.
 *
 * <h2>Validate everything, then write everything</h2>
 *
 * <p>Parsing and validation happen before this is called and touch nothing. Activation is one
 * transaction: either the whole schedule moves to its new state or none of it does. A half-applied
 * schedule is worse than a rejected one, because every operational calculation — route progress,
 * headway, deviation — is measured against it, and they would be measuring against a schedule that
 * never existed.
 *
 * <h2>Upsert by natural key, not delete-and-recreate</h2>
 *
 * <p>Routes and stops are matched on their feed ids and updated in place. Deleting and recreating
 * them would be simpler to write and would break everything pointing at them: vehicles are assigned
 * to routes, alerts and incidents reference them, and telemetry history is projected onto their
 * geometry. Re-importing a feed must not orphan a month of operational history.
 *
 * <p>Stop times are the exception: a trip's calls are replaced wholesale, because a trip whose
 * pattern changed has no meaningful row-by-row correspondence with its old one.
 */
@Service
public class ScheduleImportService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleImportService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ScheduleFeedParser parser;
    private final ScheduleFeedValidator validator;

    public ScheduleImportService(
            JdbcTemplate jdbcTemplate,
            ScheduleFeedParser parser,
            ScheduleFeedValidator validator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.parser = parser;
        this.validator = validator;
    }

    /**
     * Parses, validates and activates a feed.
     *
     * @throws ScheduleImportException listing every problem, with nothing written
     */
    @Transactional
    public ScheduleImportResult importFeed(Map<String, byte[]> files) {
        ScheduleFeed feed = parser.parse(files);
        validator.validate(feed);

        Map<String, Long> agencyIds = upsertAgencies(feed);
        Map<String, String> routeShapes = routeShapes(feed);
        Map<String, Long> routeIds = upsertRoutes(feed, agencyIds, routeShapes);
        Map<String, Long> stopIds = upsertStops(feed);
        Map<String, Long> calendarIds = upsertCalendars(feed);
        Map<String, Long> tripIds = upsertTrips(feed, routeIds, calendarIds);
        int stopTimes = replaceStopTimes(feed, tripIds, stopIds);

        log.info("Activated schedule import: {} routes, {} stops, {} trips, {} stop times.",
                routeIds.size(), stopIds.size(), tripIds.size(), stopTimes);

        return new ScheduleImportResult(
                agencyIds.size(),
                routeIds.size(),
                stopIds.size(),
                calendarIds.size(),
                tripIds.size(),
                stopTimes,
                feed.shapePoints().size());
    }

    private Map<String, Long> upsertAgencies(ScheduleFeed feed) {
        Map<String, Long> ids = new HashMap<>();
        for (ScheduleFeed.Agency agency : feed.agencies()) {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO agency (name, timezone)
                    VALUES (?, ?)
                    ON CONFLICT (name) DO UPDATE
                    SET timezone = EXCLUDED.timezone,
                        updated_at = now()
                    RETURNING id
                    """, Long.class, agency.name(), agency.timezone());
            ids.put(agency.id(), id);
        }
        return ids;
    }

    /**
     * Works out which shape belongs to each route.
     *
     * <p>GTFS attaches shapes to trips, not routes, but MetroPulse projects vehicles onto a route's
     * geometry. The first shape found among a route's trips is used, which is right when a route's
     * trips share a path and approximate when they do not — a limitation worth naming rather than
     * hiding, since a route with genuinely different patterns per direction needs per-pattern
     * geometry.
     */
    private Map<String, String> routeShapes(ScheduleFeed feed) {
        Map<String, String> shapeByRoute = new HashMap<>();
        for (ScheduleFeed.Trip trip : feed.trips()) {
            if (trip.shapeId() != null) {
                shapeByRoute.putIfAbsent(trip.routeId(), trip.shapeId());
            }
        }
        return shapeByRoute;
    }

    private Map<String, Long> upsertRoutes(
            ScheduleFeed feed,
            Map<String, Long> agencyIds,
            Map<String, String> routeShapes
    ) {
        Map<String, List<ScheduleFeed.ShapePoint>> shapes = feed.shapePoints().stream()
                .collect(Collectors.groupingBy(ScheduleFeed.ShapePoint::shapeId));

        Map<String, Long> ids = new HashMap<>();
        for (ScheduleFeed.Route route : feed.routes()) {
            Long agencyId = agencyIds.get(route.agencyId());
            if (agencyId == null && !agencyIds.isEmpty()) {
                // A feed with one agency may omit agency_id on its routes.
                agencyId = agencyIds.values().iterator().next();
            }

            String geometry = buildLineString(shapes.get(routeShapes.get(route.id())));
            if (geometry == null) {
                // Without a shape there is nothing to project onto, so the route keeps whatever
                // geometry it already had; a brand new route without a shape cannot be imported.
                Long existing = findRouteId(route.id());
                if (existing == null) {
                    throw new ScheduleImportException(List.of(
                            "Route " + route.id() + " has no shape, and a route needs geometry to be created."));
                }
                jdbcTemplate.update("""
                        UPDATE route
                        SET short_name = ?, long_name = ?, agency_id = ?, updated_at = now()
                        WHERE code = ?
                        """, route.shortName(), route.longName(), agencyId, route.id());
                ids.put(route.id(), existing);
                continue;
            }

            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO route (agency_id, code, short_name, long_name, geometry, active)
                    VALUES (?, ?, ?, ?, ST_GeomFromText(?, 4326), TRUE)
                    ON CONFLICT (code) DO UPDATE
                    SET agency_id = EXCLUDED.agency_id,
                        short_name = EXCLUDED.short_name,
                        long_name = EXCLUDED.long_name,
                        geometry = EXCLUDED.geometry,
                        active = TRUE,
                        updated_at = now()
                    RETURNING id
                    """, Long.class, agencyId, route.id(), route.shortName(), route.longName(), geometry);
            ids.put(route.id(), id);
        }
        return ids;
    }

    private Map<String, Long> upsertStops(ScheduleFeed feed) {
        Map<String, Long> ids = new HashMap<>();
        for (ScheduleFeed.Stop stop : feed.stops()) {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO stop (code, name, location)
                    VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                    ON CONFLICT (code) DO UPDATE
                    SET name = EXCLUDED.name,
                        location = EXCLUDED.location,
                        updated_at = now()
                    RETURNING id
                    """, Long.class, stop.id(), stop.name(), stop.longitude(), stop.latitude());
            ids.put(stop.id(), id);
        }
        return ids;
    }

    private Map<String, Long> upsertCalendars(ScheduleFeed feed) {
        Map<String, Long> ids = new HashMap<>();
        for (ScheduleFeed.Calendar calendar : feed.calendars()) {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO service_calendar (
                        name, start_date, end_date, monday, tuesday, wednesday, thursday, friday,
                        saturday, sunday
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (name) DO UPDATE
                    SET start_date = EXCLUDED.start_date,
                        end_date = EXCLUDED.end_date,
                        monday = EXCLUDED.monday,
                        tuesday = EXCLUDED.tuesday,
                        wednesday = EXCLUDED.wednesday,
                        thursday = EXCLUDED.thursday,
                        friday = EXCLUDED.friday,
                        saturday = EXCLUDED.saturday,
                        sunday = EXCLUDED.sunday
                    RETURNING id
                    """, Long.class,
                    calendar.id(), calendar.startDate(), calendar.endDate(),
                    calendar.monday(), calendar.tuesday(), calendar.wednesday(), calendar.thursday(),
                    calendar.friday(), calendar.saturday(), calendar.sunday());
            ids.put(calendar.id(), id);
        }
        return ids;
    }

    private Map<String, Long> upsertTrips(
            ScheduleFeed feed,
            Map<String, Long> routeIds,
            Map<String, Long> calendarIds
    ) {
        Map<String, List<ScheduleFeed.StopTime>> callsByTrip = feed.stopTimes().stream()
                .collect(Collectors.groupingBy(ScheduleFeed.StopTime::tripId));

        Map<String, Long> ids = new HashMap<>();
        for (ScheduleFeed.Trip trip : feed.trips()) {
            List<ScheduleFeed.StopTime> calls = callsByTrip.getOrDefault(trip.id(), List.of()).stream()
                    .sorted(Comparator.comparingInt(ScheduleFeed.StopTime::stopSequence))
                    .toList();

            // Planned start and end come from the trip's own calls rather than a separate column, so
            // they cannot disagree with the stop times they summarise.
            int start = calls.getFirst().arrivalSeconds();
            int end = calls.getLast().departureSeconds();

            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO trip (
                        route_id, service_calendar_id, trip_code, direction, headsign,
                        planned_start_seconds, planned_end_seconds
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (trip_code) DO UPDATE
                    SET route_id = EXCLUDED.route_id,
                        service_calendar_id = EXCLUDED.service_calendar_id,
                        direction = EXCLUDED.direction,
                        headsign = EXCLUDED.headsign,
                        planned_start_seconds = EXCLUDED.planned_start_seconds,
                        planned_end_seconds = EXCLUDED.planned_end_seconds
                    RETURNING id
                    """, Long.class,
                    routeIds.get(trip.routeId()),
                    calendarIds.get(trip.serviceId()),
                    trip.id(),
                    trip.direction(),
                    trip.headsign(),
                    start,
                    end);
            ids.put(trip.id(), id);
        }
        return ids;
    }

    /** A trip's calls are replaced wholesale: a changed pattern has no row-by-row correspondence. */
    private int replaceStopTimes(ScheduleFeed feed, Map<String, Long> tripIds, Map<String, Long> stopIds) {
        for (Long tripId : tripIds.values()) {
            jdbcTemplate.update("DELETE FROM stop_time WHERE trip_id = ?", tripId);
        }

        int inserted = 0;
        for (ScheduleFeed.StopTime call : feed.stopTimes()) {
            jdbcTemplate.update("""
                    INSERT INTO stop_time (
                        trip_id, stop_id, stop_sequence, planned_arrival_seconds, planned_departure_seconds
                    )
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    tripIds.get(call.tripId()),
                    stopIds.get(call.stopId()),
                    call.stopSequence(),
                    call.arrivalSeconds(),
                    call.departureSeconds());
            inserted++;
        }
        return inserted;
    }

    /** Well-known text for the shape, in shape_pt_sequence order. */
    private String buildLineString(List<ScheduleFeed.ShapePoint> points) {
        if (points == null || points.size() < 2) {
            return null;
        }

        String coordinates = points.stream()
                .sorted(Comparator.comparingInt(ScheduleFeed.ShapePoint::sequence))
                // WKT is longitude first, which is the opposite order to how the feed lists them.
                .map(point -> point.longitude() + " " + point.latitude())
                .collect(Collectors.joining(", "));

        return "LINESTRING(" + coordinates + ")";
    }

    private Long findRouteId(String code) {
        return jdbcTemplate.query(
                "SELECT id FROM route WHERE code = ?",
                rs -> rs.next() ? rs.getLong("id") : null,
                code);
    }
}
