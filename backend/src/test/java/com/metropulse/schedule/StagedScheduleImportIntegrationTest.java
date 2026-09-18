package com.metropulse.schedule;

import com.metropulse.schedule.importer.ScheduleImportAlreadyDecidedException;
import com.metropulse.schedule.importer.ScheduleImportException;
import com.metropulse.schedule.importer.SchedulePreview;
import com.metropulse.schedule.importer.StagedScheduleImport;
import com.metropulse.schedule.importer.StagedScheduleImportService;
import com.metropulse.schedule.importer.UnknownScheduleImportException;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Staging a feed, reviewing it, and deciding.
 *
 * <p>The assertion that carries the design is the first one: staging writes nothing operational. Every
 * number a controller reads — route progress, headway, deviation, punctuality — is measured against
 * the schedule, so a schedule that changed because someone uploaded a file would change the meaning
 * of the screen they were already looking at.
 */
class StagedScheduleImportIntegrationTest extends PostgisIntegrationTest {

    private static final String PLANNER = "planner-a";
    private static final String ADMIN = "admin-a";

    @Autowired
    private StagedScheduleImportService stagedImportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void resetImportedSchedule() {
        jdbcTemplate.update("DELETE FROM schedule_import_file");
        jdbcTemplate.update("DELETE FROM schedule_import");
        jdbcTemplate.update("DELETE FROM stop_time WHERE trip_id IN (SELECT id FROM trip WHERE trip_code LIKE 'STG-%')");
        jdbcTemplate.update("DELETE FROM trip WHERE trip_code LIKE 'STG-%'");
        jdbcTemplate.update("DELETE FROM route WHERE code LIKE 'STG-%'");
        jdbcTemplate.update("DELETE FROM stop WHERE code LIKE 'STG-%'");
        jdbcTemplate.update("DELETE FROM service_calendar WHERE name LIKE 'STG-%'");
        jdbcTemplate.update("DELETE FROM agency WHERE name = 'Staged Transit Authority'");
    }

    @Test
    void stagingWritesNothingToTheScheduleInService() {
        int routesBefore = routeCount();
        int tripsBefore = tripCount();

        StagedScheduleImport staged = stagedImportService.stage(validFeed(), PLANNER);

        assertThat(staged.isStaged()).isTrue();
        assertThat(routeCount()).isEqualTo(routesBefore);
        assertThat(tripCount()).isEqualTo(tripsBefore);
        assertThat(routeExists("STG-R1")).isFalse();
    }

    @Test
    void thePreviewSeparatesWhatIsNewFromWhatWouldBeWrittenOver() {
        SchedulePreview preview = stagedImportService.stage(validFeed(), PLANNER).preview();

        // Nothing in this feed exists yet, so everything is an addition.
        assertThat(preview.routes()).isEqualTo(new SchedulePreview.Change(1, 0));
        assertThat(preview.stops()).isEqualTo(new SchedulePreview.Change(3, 0));
        assertThat(preview.trips()).isEqualTo(new SchedulePreview.Change(1, 0));
        assertThat(preview.stopTimes()).isEqualTo(3);
    }

    @Test
    void aSecondStagingOfTheSameFeedShowsItAsAnUpdate() {
        stagedImportService.activate(stagedImportService.stage(validFeed(), PLANNER).id(), ADMIN);

        SchedulePreview preview = stagedImportService.stage(validFeed(), PLANNER).preview();

        // "One route" means something very different depending on which side of this it falls on.
        assertThat(preview.routes()).isEqualTo(new SchedulePreview.Change(0, 1));
        assertThat(preview.stops()).isEqualTo(new SchedulePreview.Change(0, 3));
    }

    @Test
    void thePreviewNamesWhatTheFeedDoesNotMention() {
        SchedulePreview preview = stagedImportService.stage(validFeed(), PLANNER).preview();

        // The seeded M42 is not in this feed and would survive the import untouched. A planner
        // approving what looks like a whole network would otherwise not know that.
        assertThat(preview.unchangedInFeed()).contains("route M42");
        assertThat(preview.notes()).anyMatch(note -> note.contains("left in place rather than removed"));
    }

    @Test
    void activatingAppliesTheFeedAndRecordsWhoDidIt() {
        StagedScheduleImport staged = stagedImportService.stage(validFeed(), PLANNER);

        StagedScheduleImport activated = stagedImportService.activate(staged.id(), ADMIN);

        assertThat(activated.status()).isEqualTo(StagedScheduleImport.ACTIVATED);
        assertThat(activated.activatedBy()).isEqualTo(ADMIN);
        assertThat(activated.uploadedBy()).as("who uploaded it is not who approved it").isEqualTo(PLANNER);
        assertThat(activated.activatedAt()).isNotNull();
        assertThat(routeExists("STG-R1")).isTrue();
    }

    @Test
    void activationRecordsWhatItActuallyDidRatherThanWhatThePreviewExpected() {
        StagedScheduleImport activated =
                stagedImportService.activate(stagedImportService.stage(validFeed(), PLANNER).id(), ADMIN);

        assertThat(activated.result()).isNotNull();
        assertThat(activated.result().routes()).isEqualTo(1);
        assertThat(activated.result().stops()).isEqualTo(3);
        assertThat(activated.result().stopTimes()).isEqualTo(3);
    }

    @Test
    void discardingLeavesTheScheduleAlone() {
        StagedScheduleImport staged = stagedImportService.stage(validFeed(), PLANNER);

        StagedScheduleImport discarded = stagedImportService.discard(staged.id(), ADMIN);

        assertThat(discarded.status()).isEqualTo(StagedScheduleImport.DISCARDED);
        assertThat(discarded.discardedBy()).isEqualTo(ADMIN);
        assertThat(routeExists("STG-R1")).isFalse();
    }

    @Test
    void anImportCannotBeActivatedTwice() {
        StagedScheduleImport staged = stagedImportService.stage(validFeed(), PLANNER);
        stagedImportService.activate(staged.id(), ADMIN);

        // Two people on the same screen: the second one is told, not quietly ignored.
        assertThatThrownBy(() -> stagedImportService.activate(staged.id(), ADMIN))
                .isInstanceOf(ScheduleImportAlreadyDecidedException.class)
                .hasMessageContaining("ACTIVATED");
    }

    @Test
    void aDiscardedImportCannotBeActivated() {
        StagedScheduleImport staged = stagedImportService.stage(validFeed(), PLANNER);
        stagedImportService.discard(staged.id(), ADMIN);

        assertThatThrownBy(() -> stagedImportService.activate(staged.id(), ADMIN))
                .isInstanceOf(ScheduleImportAlreadyDecidedException.class);
        assertThat(routeExists("STG-R1")).isFalse();
    }

    @Test
    void aBrokenFeedIsRejectedBeforeItIsEverStaged() {
        Map<String, byte[]> broken = validFeed();
        broken.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                STG-T1,07:00:00,07:00:30,STG-DOES-NOT-EXIST,1
                """));

        assertThatThrownBy(() -> stagedImportService.stage(broken, PLANNER))
                .isInstanceOf(ScheduleImportException.class);

        // Nothing staged either: a feed nobody can activate is not worth keeping for review.
        assertThat(stagedImportService.findAll()).isEmpty();
    }

    @Test
    void theUploadedFilesAreKeptSoActivationAppliesWhatWasReviewed() {
        StagedScheduleImport staged = stagedImportService.stage(validFeed(), PLANNER);

        assertThat(staged.fileNames())
                .contains("agency.txt", "routes.txt", "stops.txt", "calendar.txt", "trips.txt",
                        "stop_times.txt", "shapes.txt");
    }

    @Test
    void importsAreListedNewestFirst() {
        stagedImportService.stage(validFeed(), PLANNER);
        stagedImportService.stage(validFeed(), "planner-b");

        assertThat(stagedImportService.findAll())
                .extracting(StagedScheduleImport::uploadedBy)
                .containsExactly("planner-b", PLANNER);
    }

    @Test
    void anImportThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> stagedImportService.findById(999_999L))
                .isInstanceOf(UnknownScheduleImportException.class);
    }

    private int routeCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route", Integer.class);
    }

    private int tripCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip", Integer.class);
    }

    private boolean routeExists(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM route WHERE code = ?)", Boolean.class, code);
    }

    private Map<String, byte[]> validFeed() {
        Map<String, byte[]> files = new LinkedHashMap<>();

        files.put("agency.txt", bytes("""
                agency_id,agency_name,agency_timezone
                STG-A1,Staged Transit Authority,America/New_York
                """));

        files.put("routes.txt", bytes("""
                route_id,agency_id,route_short_name,route_long_name
                STG-R1,STG-A1,R1,Staged Crosstown
                """));

        files.put("stops.txt", bytes("""
                stop_id,stop_name,stop_lat,stop_lon
                STG-S1,North Gate,40.7000,-74.0000
                STG-S2,Mid Point,40.7100,-73.9850
                STG-S3,South Gate,40.7200,-73.9700
                """));

        files.put("calendar.txt", bytes("""
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                STG-C1,1,1,1,1,1,0,0,20260101,20261231
                """));

        files.put("trips.txt", bytes("""
                route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
                STG-R1,STG-C1,STG-T1,South Gate,0,STG-SH1
                """));

        files.put("stop_times.txt", bytes("""
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                STG-T1,07:00:00,07:00:30,STG-S1,1
                STG-T1,07:05:00,07:05:30,STG-S2,2
                STG-T1,07:10:00,07:10:00,STG-S3,3
                """));

        files.put("shapes.txt", bytes("""
                shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
                STG-SH1,40.7000,-74.0000,1
                STG-SH1,40.7050,-73.9920,2
                STG-SH1,40.7100,-73.9850,3
                STG-SH1,40.7200,-73.9700,4
                """));

        return files;
    }

    private byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
