package com.metropulse.schedule.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed feed, before anything has been written.
 *
 * <p>Parsing and validation happen entirely in memory, and the database is not touched until every
 * check has passed. A feed that is half-applied is worse than one that is rejected: the schedule is
 * what every operational calculation is measured against, so it has to move from one complete state
 * to another.
 */
public class ScheduleFeed {

    private final List<Agency> agencies = new ArrayList<>();
    private final List<Route> routes = new ArrayList<>();
    private final List<Stop> stops = new ArrayList<>();
    private final List<Calendar> calendars = new ArrayList<>();
    private final List<Trip> trips = new ArrayList<>();
    private final List<StopTime> stopTimes = new ArrayList<>();
    private final List<ShapePoint> shapePoints = new ArrayList<>();

    public List<Agency> agencies() {
        return agencies;
    }

    public List<Route> routes() {
        return routes;
    }

    public List<Stop> stops() {
        return stops;
    }

    public List<Calendar> calendars() {
        return calendars;
    }

    public List<Trip> trips() {
        return trips;
    }

    public List<StopTime> stopTimes() {
        return stopTimes;
    }

    public List<ShapePoint> shapePoints() {
        return shapePoints;
    }

    public record Agency(String id, String name, String timezone) {
    }

    public record Route(String id, String agencyId, String shortName, String longName) {
    }

    public record Stop(String id, String name, BigDecimal latitude, BigDecimal longitude) {
    }

    /** A service calendar: which days of the week this service runs, between two dates. */
    public record Calendar(
            String id,
            LocalDate startDate,
            LocalDate endDate,
            boolean monday,
            boolean tuesday,
            boolean wednesday,
            boolean thursday,
            boolean friday,
            boolean saturday,
            boolean sunday
    ) {
    }

    public record Trip(
            String id,
            String routeId,
            String serviceId,
            String shapeId,
            String direction,
            String headsign
    ) {
    }

    /**
     * One scheduled call.
     *
     * <p>Times are service-day seconds rather than clock times, because a transit day runs past
     * midnight: a trip leaving at 25:10:00 departs at 01:10 on the following calendar day but belongs
     * to the previous service day.
     */
    public record StopTime(
            String tripId,
            String stopId,
            int stopSequence,
            int arrivalSeconds,
            int departureSeconds
    ) {
    }

    public record ShapePoint(String shapeId, int sequence, BigDecimal latitude, BigDecimal longitude) {
    }
}
