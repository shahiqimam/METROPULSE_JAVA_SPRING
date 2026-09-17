package com.metropulse.schedule.importer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a GTFS-style feed.
 *
 * <p>Parsing is separate from validation: this turns text into typed rows and reports anything it
 * cannot read at all, and {@link ScheduleFeedValidator} then asks whether the rows make sense
 * together. Mixing the two produces a class where "is this a number" and "does this trip exist" live
 * side by side, and neither is easy to follow.
 *
 * <p>Uses Apache Commons CSV rather than splitting on commas. A quoted field containing a comma —
 * <em>"Main St, West"</em>, which is an ordinary stop name — silently corrupts every column after it
 * with a naive split, and the failure shows up much later as a mysterious coordinate.
 */
@Component
public class ScheduleFeedParser {

    public static final Set<String> REQUIRED_FILES = Set.of(
            "agency.txt", "routes.txt", "stops.txt", "trips.txt", "stop_times.txt", "calendar.txt");

    public static final String SHAPES_FILE = "shapes.txt";

    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

    /**
     * Reads every file into a feed.
     *
     * @param files file name to content
     * @throws ScheduleImportException if a required file is missing, a header is absent, or a value
     *                                 cannot be read as the type its column requires
     */
    public ScheduleFeed parse(Map<String, byte[]> files) {
        List<String> problems = new ArrayList<>();

        for (String required : REQUIRED_FILES) {
            if (!files.containsKey(required)) {
                problems.add("Missing required file: " + required);
            }
        }
        if (!problems.isEmpty()) {
            // Nothing else can be trusted if the file set is wrong.
            throw new ScheduleImportException(problems);
        }

        ScheduleFeed feed = new ScheduleFeed();

        parseAgencies(files, feed, problems);
        parseRoutes(files, feed, problems);
        parseStops(files, feed, problems);
        parseCalendars(files, feed, problems);
        parseTrips(files, feed, problems);
        parseStopTimes(files, feed, problems);
        if (files.containsKey(SHAPES_FILE)) {
            parseShapes(files, feed, problems);
        }

        if (!problems.isEmpty()) {
            throw new ScheduleImportException(problems);
        }
        return feed;
    }

    private void parseAgencies(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, "agency.txt", problems,
                List.of("agency_name", "agency_timezone"),
                record -> feed.agencies().add(new ScheduleFeed.Agency(
                        optional(record, "agency_id", "agency"),
                        record.get("agency_name"),
                        record.get("agency_timezone"))));
    }

    private void parseRoutes(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, "routes.txt", problems,
                List.of("route_id", "route_short_name", "route_long_name"),
                record -> feed.routes().add(new ScheduleFeed.Route(
                        record.get("route_id"),
                        optional(record, "agency_id", "agency"),
                        record.get("route_short_name"),
                        record.get("route_long_name"))));
    }

    private void parseStops(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, "stops.txt", problems,
                List.of("stop_id", "stop_name", "stop_lat", "stop_lon"),
                record -> feed.stops().add(new ScheduleFeed.Stop(
                        record.get("stop_id"),
                        record.get("stop_name"),
                        decimal(record, "stop_lat"),
                        decimal(record, "stop_lon"))));
    }

    private void parseCalendars(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, "calendar.txt", problems,
                List.of("service_id", "monday", "tuesday", "wednesday", "thursday", "friday",
                        "saturday", "sunday", "start_date", "end_date"),
                record -> feed.calendars().add(new ScheduleFeed.Calendar(
                        record.get("service_id"),
                        date(record, "start_date"),
                        date(record, "end_date"),
                        flag(record, "monday"),
                        flag(record, "tuesday"),
                        flag(record, "wednesday"),
                        flag(record, "thursday"),
                        flag(record, "friday"),
                        flag(record, "saturday"),
                        flag(record, "sunday"))));
    }

    private void parseTrips(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, "trips.txt", problems,
                List.of("route_id", "service_id", "trip_id"),
                record -> feed.trips().add(new ScheduleFeed.Trip(
                        record.get("trip_id"),
                        record.get("route_id"),
                        record.get("service_id"),
                        optional(record, "shape_id", null),
                        "1".equals(optional(record, "direction_id", "0")) ? "INBOUND" : "OUTBOUND",
                        optional(record, "trip_headsign", ""))));
    }

    private void parseStopTimes(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, "stop_times.txt", problems,
                List.of("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"),
                record -> feed.stopTimes().add(new ScheduleFeed.StopTime(
                        record.get("trip_id"),
                        record.get("stop_id"),
                        integer(record, "stop_sequence"),
                        serviceDaySeconds(record, "arrival_time"),
                        serviceDaySeconds(record, "departure_time"))));
    }

    private void parseShapes(Map<String, byte[]> files, ScheduleFeed feed, List<String> problems) {
        forEachRecord(files, SHAPES_FILE, problems,
                List.of("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"),
                record -> feed.shapePoints().add(new ScheduleFeed.ShapePoint(
                        record.get("shape_id"),
                        integer(record, "shape_pt_sequence"),
                        decimal(record, "shape_pt_lat"),
                        decimal(record, "shape_pt_lon"))));
    }

    /** Reads one file, checking its headers first and reporting the row number of anything unreadable. */
    private void forEachRecord(
            Map<String, byte[]> files,
            String fileName,
            List<String> problems,
            List<String> requiredHeaders,
            RecordHandler handler
    ) {
        byte[] content = files.get(fileName);
        if (content == null) {
            return;
        }

        try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, FORMAT)) {

            for (String header : requiredHeaders) {
                if (!parser.getHeaderMap().containsKey(header)) {
                    problems.add(fileName + " is missing the column: " + header);
                }
            }
            if (!problems.isEmpty()) {
                return;
            }

            for (CSVRecord record : parser) {
                try {
                    handler.handle(record);
                } catch (RuntimeException ex) {
                    // Row number, not byte offset: it is what the planner sees in their spreadsheet.
                    problems.add(fileName + " line " + record.getRecordNumber() + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            problems.add(fileName + " could not be read: " + ex.getMessage());
        }
    }

    private String optional(CSVRecord record, String column, String fallback) {
        if (!record.isMapped(column)) {
            return fallback;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? fallback : value;
    }

    private BigDecimal decimal(CSVRecord record, String column) {
        String value = record.get(column);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(column + " is not a number: " + value);
        }
    }

    private int integer(CSVRecord record, String column) {
        String value = record.get(column);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(column + " is not a whole number: " + value);
        }
    }

    private LocalDate date(CSVRecord record, String column) {
        String value = record.get(column);
        try {
            return LocalDate.parse(value, GTFS_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(column + " is not a yyyyMMdd date: " + value);
        }
    }

    private boolean flag(CSVRecord record, String column) {
        return "1".equals(record.get(column));
    }

    /**
     * Parses {@code HH:MM:SS} into seconds since the start of the service day.
     *
     * <p>Hours may exceed 24 and that is not an error: a trip leaving at 25:10:00 departs at 01:10 the
     * next calendar morning but belongs to the previous service day. Normalising it to 01:10 would
     * put it at the start of its own day and make every schedule comparison wrong.
     */
    private int serviceDaySeconds(CSVRecord record, String column) {
        String value = record.get(column);
        String[] parts = value.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException(column + " is not HH:MM:SS: " + value);
        }

        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);

            if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
                throw new IllegalArgumentException(column + " is not a valid time: " + value);
            }
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(column + " is not HH:MM:SS: " + value);
        }
    }

    @FunctionalInterface
    private interface RecordHandler {
        void handle(CSVRecord record);
    }
}
