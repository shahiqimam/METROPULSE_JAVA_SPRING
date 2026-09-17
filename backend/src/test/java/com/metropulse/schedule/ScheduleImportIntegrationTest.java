package com.metropulse.schedule;

import com.metropulse.schedule.importer.ScheduleImportException;
import com.metropulse.schedule.importer.ScheduleImportResult;
import com.metropulse.schedule.importer.ScheduleImportService;
import com.metropulse.schedule.read.RouteGeometryPoint;
import com.metropulse.schedule.read.RouteStop;
import com.metropulse.schedule.read.ScheduleQueryService;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Importing a GTFS-style feed.
 *
 * <p>The tests that matter most are the rejections. An importer that accepts a valid feed is the easy
 * half; one that refuses a broken feed <em>without writing anything</em> is what keeps the schedule
 * trustworthy, because every operational calculation is measured against it.
 */
class ScheduleImportIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private ScheduleImportService importService;

    @Autowired
    private ScheduleQueryService scheduleQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void resetImportedSchedule() {
        // Leave the seeded M42 alone; these tests import their own route.
        //
        // Cleaning up afterwards as well as before matters: every integration test shares one
        // database, so a class that leaves rows behind breaks whichever class runs next and asserts
        // on how many routes exist.
        jdbcTemplate.update("DELETE FROM stop_time WHERE trip_id IN (SELECT id FROM trip WHERE trip_code LIKE 'IMP-%')");
        jdbcTemplate.update("DELETE FROM trip WHERE trip_code LIKE 'IMP-%'");
        jdbcTemplate.update("DELETE FROM route WHERE code LIKE 'IMP-%'");
        jdbcTemplate.update("DELETE FROM stop WHERE code LIKE 'IMP-%'");
        jdbcTemplate.update("DELETE FROM service_calendar WHERE name LIKE 'IMP-%'");
        jdbcTemplate.update("DELETE FROM agency WHERE name = 'Imported Transit Authority'");
    }

    @Test
    void aValidFeedIsImported() {
        ScheduleImportResult result = importService.importFeed(validFeed());

        assertThat(result.routes()).isEqualTo(1);
        assertThat(result.stops()).isEqualTo(3);
        assertThat(result.trips()).isEqualTo(1);
        assertThat(result.stopTimes()).isEqualTo(3);
        assertThat(result.shapePoints()).isEqualTo(4);
    }

    @Test
    void theImportedRouteCarriesItsShapeAsPostgisGeometry() {
        importService.importFeed(validFeed());

        List<RouteGeometryPoint> geometry = scheduleQueryService.findRouteGeometry("IMP-R1");

        assertThat(geometry).hasSize(4);
        assertThat(geometry.getFirst().latitude()).isEqualByComparingTo("40.7000");
        assertThat(geometry.getLast().longitude()).isEqualByComparingTo("-73.9700");
        // Ordered by shape_pt_sequence, not by file order.
        assertThat(geometry).extracting(RouteGeometryPoint::sequence).containsExactly(1, 2, 3, 4);
    }

    @Test
    void stopTimesAreImportedInSequenceWithServiceDaySeconds() {
        importService.importFeed(validFeed());

        List<RouteStop> stops = scheduleQueryService.findRouteStops("IMP-R1");

        assertThat(stops).extracting(RouteStop::stopSequence).containsExactly(1, 2, 3);
        assertThat(stops.getFirst().stopName()).isEqualTo("North Gate");
        // 07:00:00 is 25200 seconds into the service day.
        assertThat(stops.getFirst().plannedArrivalSeconds()).isEqualTo(25_200);
    }

    @Test
    void aTimePastMidnightKeepsItsServiceDayHours() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                IMP-T1,25:00:00,25:00:30,IMP-S1,1
                IMP-T1,25:05:00,25:05:30,IMP-S2,2
                IMP-T1,25:10:00,25:10:00,IMP-S3,3
                """));

        importService.importFeed(feed);

        // 25:00:00 is 90000 seconds, not 3600: it belongs to the previous service day.
        assertThat(scheduleQueryService.findRouteStops("IMP-R1").getFirst().plannedArrivalSeconds())
                .isEqualTo(90_000);
    }

    @Test
    void aStopNameContainingACommaSurvivesParsing() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat,stop_lon
                IMP-S1,"Main St, West",40.7000,-74.0000
                IMP-S2,Mid Point,40.7100,-73.9850
                IMP-S3,South Gate,40.7200,-73.9700
                """));

        importService.importFeed(feed);

        // A naive split on commas would corrupt every column after the name.
        assertThat(scheduleQueryService.findRouteStops("IMP-R1").getFirst().stopName())
                .isEqualTo("Main St, West");
    }

    @Test
    void reimportingUpdatesInPlaceRatherThanDuplicating() {
        importService.importFeed(validFeed());

        Map<String, byte[]> updated = validFeed();
        updated.put("routes.txt", bytes("""
                route_id,agency_id,route_short_name,route_long_name
                IMP-R1,IMP-A1,R1,Renamed Crosstown
                """));
        importService.importFeed(updated);

        assertThat(scheduleQueryService.findRoutes())
                .filteredOn(route -> route.code().equals("IMP-R1"))
                .singleElement()
                .satisfies(route -> assertThat(route.longName()).isEqualTo("Renamed Crosstown"));
        assertThat(scheduleQueryService.findRouteStops("IMP-R1")).hasSize(3);
    }

    @Test
    void aMissingRequiredFileIsRejected() {
        Map<String, byte[]> feed = validFeed();
        feed.remove("calendar.txt");

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .anyMatch(problem -> problem.contains("calendar.txt")));
    }

    @Test
    void aMissingColumnIsRejected() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat
                IMP-S1,North Gate,40.7000
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .anyMatch(problem -> problem.contains("stop_lon")));
    }

    @Test
    void aTripReferencingAnUnknownRouteIsRejected() {
        Map<String, byte[]> feed = validFeed();
        feed.put("trips.txt", bytes("""
                route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
                IMP-GHOST,IMP-C1,IMP-T1,South Gate,0,IMP-SH1
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .anyMatch(problem -> problem.contains("unknown route_id IMP-GHOST")));
    }

    @Test
    void impossibleCoordinatesAreRejected() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat,stop_lon
                IMP-S1,North Gate,91.0,-74.0000
                IMP-S2,Mid Point,40.7100,-73.9850
                IMP-S3,South Gate,0,0
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> {
                    List<String> problems = ((ScheduleImportException) ex).problems();
                    assertThat(problems).anyMatch(problem -> problem.contains("latitude outside"));
                    assertThat(problems).anyMatch(problem -> problem.contains("0,0"));
                });
    }

    @Test
    void stopTimesThatGoBackwardsAreRejected() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                IMP-T1,07:00:00,07:00:30,IMP-S1,1
                IMP-T1,06:55:00,06:55:30,IMP-S2,2
                IMP-T1,07:10:00,07:10:00,IMP-S3,3
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .anyMatch(problem -> problem.contains("before it left the previous stop")));
    }

    @Test
    void aDepartureBeforeItsArrivalIsRejected() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                IMP-T1,07:00:00,06:59:00,IMP-S1,1
                IMP-T1,07:05:00,07:05:30,IMP-S2,2
                IMP-T1,07:10:00,07:10:00,IMP-S3,3
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .anyMatch(problem -> problem.contains("before it arrives")));
    }

    @Test
    void anUnreadableNumberIsReportedWithItsLineNumber() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat,stop_lon
                IMP-S1,North Gate,not-a-number,-74.0000
                IMP-S2,Mid Point,40.7100,-73.9850
                IMP-S3,South Gate,40.7200,-73.9700
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .anyMatch(problem -> problem.contains("line 1") && problem.contains("stop_lat")));
    }

    @Test
    void everyProblemIsReportedTogetherRatherThanOneAtATime() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat,stop_lon
                IMP-S1,North Gate,95.0,-74.0000
                IMP-S2,Mid Point,40.7100,-200.0
                IMP-S3,South Gate,40.7200,-73.9700
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class)
                .satisfies(ex -> assertThat(((ScheduleImportException) ex).problems())
                        .as("a planner fixing a feed wants the whole list")
                        .hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void aRejectedImportWritesNothing() {
        Map<String, byte[]> feed = validFeed();
        feed.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                IMP-T1,07:00:00,07:00:30,IMP-S1,1
                IMP-T1,06:00:00,06:00:30,IMP-S2,2
                IMP-T1,07:10:00,07:10:00,IMP-S3,3
                """));

        assertThatThrownBy(() -> importService.importFeed(feed))
                .isInstanceOf(ScheduleImportException.class);

        // Validation runs before anything is written, so not even the valid parts landed.
        assertThat(countLike("route", "code", "IMP-%")).isZero();
        assertThat(countLike("stop", "code", "IMP-%")).isZero();
        assertThat(countLike("trip", "trip_code", "IMP-%")).isZero();
    }

    @Test
    void theSeededScheduleIsUntouchedByAnImportOfADifferentRoute() {
        importService.importFeed(validFeed());

        // The development fleet is assigned to M42; an import must not orphan those assignments.
        assertThat(scheduleQueryService.findRouteStops("M42")).hasSize(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle WHERE assigned_route_id IS NOT NULL", Integer.class))
                .isEqualTo(4);
    }

    private int countLike(String table, String column, String pattern) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE ?", Integer.class, pattern);
    }

    /** A small, complete, valid feed. Each test breaks exactly one thing in it. */
    private Map<String, byte[]> validFeed() {
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("agency.txt", bytes("""
                agency_id,agency_name,agency_timezone
                IMP-A1,Imported Transit Authority,America/New_York
                """));

        files.put("routes.txt", bytes("""
                route_id,agency_id,route_short_name,route_long_name
                IMP-R1,IMP-A1,R1,Imported Crosstown
                """));

        files.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat,stop_lon
                IMP-S1,North Gate,40.7000,-74.0000
                IMP-S2,Mid Point,40.7100,-73.9850
                IMP-S3,South Gate,40.7200,-73.9700
                """));

        files.put("calendar.txt", bytes("""
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                IMP-C1,1,1,1,1,1,0,0,20260101,20261231
                """));

        files.put("trips.txt", bytes("""
                route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
                IMP-R1,IMP-C1,IMP-T1,South Gate,0,IMP-SH1
                """));

        files.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                IMP-T1,07:00:00,07:00:30,IMP-S1,1
                IMP-T1,07:05:00,07:05:30,IMP-S2,2
                IMP-T1,07:10:00,07:10:00,IMP-S3,3
                """));

        files.put("shapes.txt", bytes("""
                shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
                IMP-SH1,40.7000,-74.0000,1
                IMP-SH1,40.7050,-73.9920,2
                IMP-SH1,40.7100,-73.9850,3
                IMP-SH1,40.7200,-73.9700,4
                """));

        return files;
    }

    private byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
