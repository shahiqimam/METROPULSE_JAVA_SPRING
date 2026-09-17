package com.metropulse.schedule.importer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asks whether a parsed feed makes sense.
 *
 * <p>Parsing answers "is this readable"; this answers "is this a schedule". The distinction matters
 * because the failures are different in kind: a bad number is a typo in one cell, while a trip
 * referencing a route that does not exist means two files disagree.
 *
 * <p>Every check reports the specific id or file involved, and all of them run before anything is
 * rejected, so a planner gets the whole list rather than one problem per attempt.
 */
@Component
public class ScheduleFeedValidator {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    /** Two days. A service day can exceed 24 hours, but not by much. */
    private static final int MAX_SERVICE_SECONDS = 172_800;

    public void validate(ScheduleFeed feed) {
        List<String> problems = new ArrayList<>();

        validatePresence(feed, problems);
        validateUniqueIds(feed, problems);
        validateCoordinates(feed, problems);
        validateCalendars(feed, problems);
        validateReferences(feed, problems);
        validateStopTimes(feed, problems);
        validateShapes(feed, problems);

        if (!problems.isEmpty()) {
            throw new ScheduleImportException(problems);
        }
    }

    private void validatePresence(ScheduleFeed feed, List<String> problems) {
        if (feed.agencies().isEmpty()) {
            problems.add("agency.txt contains no rows.");
        }
        if (feed.routes().isEmpty()) {
            problems.add("routes.txt contains no rows.");
        }
        if (feed.stops().isEmpty()) {
            problems.add("stops.txt contains no rows.");
        }
        if (feed.trips().isEmpty()) {
            problems.add("trips.txt contains no rows.");
        }
        if (feed.stopTimes().isEmpty()) {
            problems.add("stop_times.txt contains no rows.");
        }
    }

    private void validateUniqueIds(ScheduleFeed feed, List<String> problems) {
        reportDuplicates(feed.routes().stream().map(ScheduleFeed.Route::id).toList(),
                "routes.txt has a duplicate route_id", problems);
        reportDuplicates(feed.stops().stream().map(ScheduleFeed.Stop::id).toList(),
                "stops.txt has a duplicate stop_id", problems);
        reportDuplicates(feed.trips().stream().map(ScheduleFeed.Trip::id).toList(),
                "trips.txt has a duplicate trip_id", problems);
        reportDuplicates(feed.calendars().stream().map(ScheduleFeed.Calendar::id).toList(),
                "calendar.txt has a duplicate service_id", problems);
    }

    private void validateCoordinates(ScheduleFeed feed, List<String> problems) {
        for (ScheduleFeed.Stop stop : feed.stops()) {
            if (outside(stop.latitude(), MIN_LATITUDE, MAX_LATITUDE)) {
                problems.add("stops.txt stop " + stop.id() + " has a latitude outside -90..90: " + stop.latitude());
            }
            if (outside(stop.longitude(), MIN_LONGITUDE, MAX_LONGITUDE)) {
                problems.add("stops.txt stop " + stop.id() + " has a longitude outside -180..180: " + stop.longitude());
            }
            // 0,0 is in the Atlantic. It is almost always a missing value rather than a real stop.
            if (stop.latitude().signum() == 0 && stop.longitude().signum() == 0) {
                problems.add("stops.txt stop " + stop.id() + " is at 0,0, which is usually a missing coordinate.");
            }
        }
    }

    private void validateCalendars(ScheduleFeed feed, List<String> problems) {
        for (ScheduleFeed.Calendar calendar : feed.calendars()) {
            if (calendar.endDate().isBefore(calendar.startDate())) {
                problems.add("calendar.txt service " + calendar.id() + " ends before it starts.");
            }
            boolean runsAtAll = calendar.monday() || calendar.tuesday() || calendar.wednesday()
                    || calendar.thursday() || calendar.friday() || calendar.saturday() || calendar.sunday();
            if (!runsAtAll) {
                problems.add("calendar.txt service " + calendar.id() + " runs on no day of the week.");
            }
        }
    }

    private void validateReferences(ScheduleFeed feed, List<String> problems) {
        Set<String> agencyIds = feed.agencies().stream().map(ScheduleFeed.Agency::id).collect(Collectors.toSet());
        Set<String> routeIds = feed.routes().stream().map(ScheduleFeed.Route::id).collect(Collectors.toSet());
        Set<String> stopIds = feed.stops().stream().map(ScheduleFeed.Stop::id).collect(Collectors.toSet());
        Set<String> tripIds = feed.trips().stream().map(ScheduleFeed.Trip::id).collect(Collectors.toSet());
        Set<String> serviceIds = feed.calendars().stream().map(ScheduleFeed.Calendar::id).collect(Collectors.toSet());

        for (ScheduleFeed.Route route : feed.routes()) {
            if (route.agencyId() != null && !agencyIds.contains(route.agencyId())) {
                problems.add("routes.txt route " + route.id() + " references unknown agency_id " + route.agencyId());
            }
        }

        for (ScheduleFeed.Trip trip : feed.trips()) {
            if (!routeIds.contains(trip.routeId())) {
                problems.add("trips.txt trip " + trip.id() + " references unknown route_id " + trip.routeId());
            }
            if (!serviceIds.contains(trip.serviceId())) {
                problems.add("trips.txt trip " + trip.id() + " references unknown service_id " + trip.serviceId());
            }
        }

        for (ScheduleFeed.StopTime stopTime : feed.stopTimes()) {
            if (!tripIds.contains(stopTime.tripId())) {
                problems.add("stop_times.txt references unknown trip_id " + stopTime.tripId());
            }
            if (!stopIds.contains(stopTime.stopId())) {
                problems.add("stop_times.txt references unknown stop_id " + stopTime.stopId());
            }
        }

        // A trip with no calls is not a trip. It would import cleanly and then represent nothing.
        Set<String> tripsWithStopTimes = feed.stopTimes().stream()
                .map(ScheduleFeed.StopTime::tripId)
                .collect(Collectors.toSet());
        for (String tripId : tripIds) {
            if (!tripsWithStopTimes.contains(tripId)) {
                problems.add("trips.txt trip " + tripId + " has no rows in stop_times.txt.");
            }
        }
    }

    private void validateStopTimes(ScheduleFeed feed, List<String> problems) {
        Map<String, List<ScheduleFeed.StopTime>> byTrip = feed.stopTimes().stream()
                .collect(Collectors.groupingBy(ScheduleFeed.StopTime::tripId));

        for (Map.Entry<String, List<ScheduleFeed.StopTime>> entry : byTrip.entrySet()) {
            String tripId = entry.getKey();
            List<ScheduleFeed.StopTime> calls = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ScheduleFeed.StopTime::stopSequence))
                    .toList();

            if (calls.size() < 2) {
                problems.add("Trip " + tripId + " has fewer than two calls; a trip needs an origin and a destination.");
            }

            Set<Integer> sequences = new HashSet<>();
            int previousDeparture = Integer.MIN_VALUE;

            for (ScheduleFeed.StopTime call : calls) {
                if (!sequences.add(call.stopSequence())) {
                    problems.add("Trip " + tripId + " repeats stop_sequence " + call.stopSequence() + ".");
                }
                if (call.stopSequence() < 0) {
                    problems.add("Trip " + tripId + " has a negative stop_sequence.");
                }
                if (call.departureSeconds() < call.arrivalSeconds()) {
                    problems.add("Trip " + tripId + " departs stop " + call.stopId() + " before it arrives.");
                }
                if (call.arrivalSeconds() > MAX_SERVICE_SECONDS) {
                    problems.add("Trip " + tripId + " has a time beyond two service days at stop " + call.stopId() + ".");
                }
                // Times must not go backwards along the trip: a vehicle cannot reach its third stop
                // before its second.
                if (previousDeparture != Integer.MIN_VALUE && call.arrivalSeconds() < previousDeparture) {
                    problems.add("Trip " + tripId + " arrives at stop " + call.stopId()
                            + " before it left the previous stop.");
                }
                previousDeparture = call.departureSeconds();
            }
        }
    }

    private void validateShapes(ScheduleFeed feed, List<String> problems) {
        if (feed.shapePoints().isEmpty()) {
            return;
        }

        Map<String, List<ScheduleFeed.ShapePoint>> byShape = feed.shapePoints().stream()
                .collect(Collectors.groupingBy(ScheduleFeed.ShapePoint::shapeId));

        for (Map.Entry<String, List<ScheduleFeed.ShapePoint>> entry : byShape.entrySet()) {
            if (entry.getValue().size() < 2) {
                problems.add("Shape " + entry.getKey() + " has fewer than two points, so it is not a line.");
            }
            for (ScheduleFeed.ShapePoint point : entry.getValue()) {
                if (outside(point.latitude(), MIN_LATITUDE, MAX_LATITUDE)
                        || outside(point.longitude(), MIN_LONGITUDE, MAX_LONGITUDE)) {
                    problems.add("Shape " + entry.getKey() + " has a point outside valid coordinates.");
                    break;
                }
            }
        }

        Set<String> shapeIds = byShape.keySet();
        for (ScheduleFeed.Trip trip : feed.trips()) {
            if (trip.shapeId() != null && !shapeIds.contains(trip.shapeId())) {
                problems.add("trips.txt trip " + trip.id() + " references unknown shape_id " + trip.shapeId());
            }
        }
    }

    private void reportDuplicates(List<String> ids, String message, List<String> problems) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id) && reported.add(id)) {
                problems.add(message + ": " + id);
            }
        }
    }

    private boolean outside(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0;
    }
}
