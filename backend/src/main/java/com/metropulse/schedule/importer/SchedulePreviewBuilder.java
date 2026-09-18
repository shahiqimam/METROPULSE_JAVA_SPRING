package com.metropulse.schedule.importer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out what a staged feed would change, without changing anything.
 *
 * <p>Every comparison is made on the same natural key the importer writes on — agencies by name,
 * routes and stops by code, calendars by name, trips by code. Anything else would describe a
 * different import from the one that would actually run.
 *
 * <p>The preview is a snapshot of a comparison at a moment in time. If the schedule changes between
 * staging and activation the preview is stale, which is why activation re-parses, re-validates and
 * reports what it actually did rather than replaying what the preview promised.
 */
@Component
public class SchedulePreviewBuilder {

    private final JdbcTemplate jdbcTemplate;

    public SchedulePreviewBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SchedulePreview describe(ScheduleFeed feed) {
        Set<String> existingAgencies = existing("SELECT name FROM agency");
        Set<String> existingRoutes = existing("SELECT code FROM route");
        Set<String> existingStops = existing("SELECT code FROM stop");
        Set<String> existingCalendars = existing("SELECT name FROM service_calendar");
        Set<String> existingTrips = existing("SELECT trip_code FROM trip");

        List<String> feedRoutes = feed.routes().stream().map(ScheduleFeed.Route::id).toList();
        List<String> feedStops = feed.stops().stream().map(ScheduleFeed.Stop::id).toList();

        List<String> untouched = new ArrayList<>();
        existingRoutes.stream().filter(code -> !feedRoutes.contains(code))
                .sorted().forEach(code -> untouched.add("route " + code));
        existingStops.stream().filter(code -> !feedStops.contains(code))
                .sorted().forEach(code -> untouched.add("stop " + code));

        return new SchedulePreview(
                change(feed.agencies().stream().map(ScheduleFeed.Agency::name).toList(), existingAgencies),
                change(feedRoutes, existingRoutes),
                change(feedStops, existingStops),
                change(feed.calendars().stream().map(ScheduleFeed.Calendar::id).toList(), existingCalendars),
                change(feed.trips().stream().map(ScheduleFeed.Trip::id).toList(), existingTrips),
                feed.stopTimes().size(),
                feed.shapePoints().size(),
                untouched,
                notes(feed, untouched));
    }

    /**
     * Things a planner should read before approving, in the planner's own terms.
     *
     * <p>These are not validation errors — a feed that reaches this point has already passed
     * validation. They are the consequences of importing it that are easy to miss, which is exactly
     * what a preview is for.
     */
    private List<String> notes(ScheduleFeed feed, List<String> untouched) {
        List<String> notes = new ArrayList<>();

        if (!untouched.isEmpty()) {
            notes.add(untouched.size() + " existing route(s) or stop(s) are not in this feed. They are "
                    + "left in place rather than removed, because vehicles are assigned to routes and "
                    + "history is projected onto their geometry.");
        }

        long tripsWithoutCalls = feed.trips().stream()
                .map(ScheduleFeed.Trip::id)
                .filter(tripId -> feed.stopTimes().stream().noneMatch(call -> call.tripId().equals(tripId)))
                .count();
        if (tripsWithoutCalls > 0) {
            notes.add(tripsWithoutCalls + " trip(s) in this feed have no stop times, so nothing on them "
                    + "can be measured for punctuality.");
        }

        Set<String> shapeIds = feed.shapePoints().stream()
                .map(ScheduleFeed.ShapePoint::shapeId)
                .collect(java.util.stream.Collectors.toSet());
        long tripsWithoutShape = feed.trips().stream()
                .filter(trip -> trip.shapeId() == null || !shapeIds.contains(trip.shapeId()))
                .count();
        if (tripsWithoutShape > 0) {
            notes.add(tripsWithoutShape + " trip(s) have no shape in this feed. Their route keeps the "
                    + "geometry it already has, which vehicles are still projected onto.");
        }

        return notes;
    }

    private SchedulePreview.Change change(Collection<String> feedKeys, Set<String> existing) {
        Set<String> distinct = new HashSet<>(feedKeys);
        int updated = (int) distinct.stream().filter(existing::contains).count();
        return new SchedulePreview.Change(distinct.size() - updated, updated);
    }

    private Set<String> existing(String sql) {
        return new HashSet<>(jdbcTemplate.queryForList(sql, String.class));
    }
}
