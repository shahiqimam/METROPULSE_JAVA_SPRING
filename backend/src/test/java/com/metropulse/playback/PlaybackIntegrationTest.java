package com.metropulse.playback;

import com.metropulse.operations.consumer.TelemetryEventHandler;
import com.metropulse.playback.api.CreatePlaybackSessionRequest;
import com.metropulse.playback.application.PlaybackService;
import com.metropulse.playback.domain.PlaybackFrame;
import com.metropulse.playback.domain.PlaybackSession;
import com.metropulse.playback.domain.UnknownPlaybackSessionException;
import com.metropulse.support.OutboxPipeline;
import com.metropulse.support.PostgisIntegrationTest;
import com.metropulse.telemetry.api.TelemetryIngestRequest;
import com.metropulse.telemetry.application.TelemetryIngestionService;
import com.metropulse.telemetry.domain.UnknownVehicleException;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replay of stored history.
 *
 * <p>The assertion that matters most is the one about live state: a replay must not make the control
 * centre believe a vehicle is where it was an hour ago.
 */
class PlaybackIntegrationTest extends PostgisIntegrationTest {

    private static final String INGEST_KEY = "test-ingest-key";
    private static final Instant WINDOW_START = Instant.parse("2026-09-16T09:00:00Z");

    @Autowired
    private PlaybackService playbackService;

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private TelemetryQueryService telemetryQueryService;

    @Autowired
    private OutboxPipeline outboxPipeline;

    @Autowired
    private TelemetryEventHandler eventHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM playback_session");
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void aSessionReportsHowManyFramesFallInItsWindow() {
        recordHistory("BUS-042", 10);

        PlaybackSession session = createSession("BUS-042", null);

        assertThat(session.frameCount()).isEqualTo(10);
        assertThat(session.vehicleId()).isEqualTo("BUS-042");
        assertThat(session.speed()).isEqualTo(5.0);
        assertThat(session.createdBy()).isEqualTo("controller-a");
    }

    @Test
    void framesComeBackInTheOrderTheyWereRecorded() {
        recordHistory("BUS-042", 6);
        PlaybackSession session = createSession("BUS-042", null);

        List<PlaybackFrame> frames = playbackService.findFrames(session.id(), 0, 100);

        assertThat(frames).hasSize(6);
        assertThat(frames).extracting(PlaybackFrame::recordedAt).isSorted();
        assertThat(frames.getFirst().recordedAt()).isEqualTo(WINDOW_START);
    }

    @Test
    void framesArePaged() {
        recordHistory("BUS-042", 10);
        PlaybackSession session = createSession("BUS-042", null);

        List<PlaybackFrame> firstPage = playbackService.findFrames(session.id(), 0, 4);
        List<PlaybackFrame> secondPage = playbackService.findFrames(session.id(), 4, 4);

        assertThat(firstPage).hasSize(4);
        assertThat(secondPage).hasSize(4);
        assertThat(secondPage.getFirst().recordedAt()).isAfter(firstPage.getLast().recordedAt());
    }

    @Test
    void aPageCannotBeLargerThanTheCap() {
        recordHistory("BUS-042", 3);
        PlaybackSession session = createSession("BUS-042", null);

        assertThat(playbackService.findFrames(session.id(), 0, 100_000))
                .as("an oversized page request is clamped, not honoured")
                .hasSize(3);
    }

    @Test
    void aSessionForOneVehicleLeavesOtherVehiclesOut() {
        recordHistory("BUS-042", 4);
        recordHistory("BUS-101", 4);

        PlaybackSession session = createSession("BUS-042", null);

        assertThat(playbackService.findFrames(session.id(), 0, 100))
                .extracting(PlaybackFrame::vehicleId)
                .containsOnly("BUS-042");
    }

    @Test
    void aSessionForARouteIncludesEveryVehicleAssignedToIt() {
        recordHistory("BUS-042", 3);
        recordHistory("BUS-101", 3);

        PlaybackSession session = createSession(null, "M42");

        assertThat(playbackService.findFrames(session.id(), 0, 100))
                .extracting(PlaybackFrame::vehicleId)
                .containsOnly("BUS-042", "BUS-101");
    }

    @Test
    void observationsOutsideTheWindowAreNotReplayed() {
        recordHistory("BUS-042", 5);
        // An hour later, well outside the requested window.
        ingest("BUS-042", "evt-late", WINDOW_START.plus(Duration.ofHours(2)), 40.75, -73.95);

        PlaybackSession session = createSession("BUS-042", null);

        assertThat(session.frameCount()).isEqualTo(5);
        assertThat(playbackService.findFrames(session.id(), 0, 100)).hasSize(5);
    }

    @Test
    void replayingDoesNotChangeLiveVehicleState() {
        recordHistory("BUS-042", 5);

        // Current state reflects a much more recent observation.
        ingest("BUS-042", "evt-now", Instant.now(), 40.7178, -73.9900);
        outboxPipeline.drain();
        LatestVehicleTelemetry before = currentState();

        PlaybackSession session = createSession("BUS-042", null);
        playbackService.findFrames(session.id(), 0, 100);

        LatestVehicleTelemetry after = currentState();
        assertThat(after.sourceEventId()).isEqualTo(before.sourceEventId());
        assertThat(after.recordedAt()).isEqualTo(before.recordedAt());
        assertThat(after.latitude()).isEqualByComparingTo(before.latitude());
    }

    @Test
    void replayingProducesNoEventsForConsumersToProcess() {
        recordHistory("BUS-042", 5);
        outboxPipeline.drain();
        int processedBefore = processedEventCount();

        PlaybackSession session = createSession("BUS-042", null);
        playbackService.findFrames(session.id(), 0, 100);

        assertThat(outboxPipeline.drain()).as("replay writes no outbox events").isEmpty();
        assertThat(processedEventCount()).isEqualTo(processedBefore);
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> playbackService.createSession(new CreatePlaybackSessionRequest(
                "BUS-042", null, WINDOW_START, WINDOW_START.minusSeconds(1), 1.0), "controller-a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWindowLongerThanTheCapIsRefused() {
        assertThatThrownBy(() -> playbackService.createSession(new CreatePlaybackSessionRequest(
                "BUS-042", null, WINDOW_START, WINDOW_START.plus(PlaybackService.MAX_WINDOW).plusSeconds(1), 1.0),
                "controller-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void aSessionForAVehicleThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> playbackService.createSession(new CreatePlaybackSessionRequest(
                "BUS-ghost", null, WINDOW_START, WINDOW_START.plusSeconds(600), 1.0), "controller-a"))
                .isInstanceOf(UnknownVehicleException.class);
    }

    @Test
    void readingASessionThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> playbackService.findSession(999_999L))
                .isInstanceOf(UnknownPlaybackSessionException.class);
    }

    private PlaybackSession createSession(String vehicleId, String routeCode) {
        return playbackService.createSession(new CreatePlaybackSessionRequest(
                vehicleId, routeCode, WINDOW_START, WINDOW_START.plus(Duration.ofMinutes(30)), 5.0),
                "controller-a");
    }

    /** Ingests a run of observations inside the playback window. */
    private void recordHistory(String vehicleId, int count) {
        for (int index = 0; index < count; index++) {
            ingest(vehicleId,
                    vehicleId + "-hist-" + index,
                    WINDOW_START.plusSeconds(index * 60L),
                    40.7128 + index * 0.001,
                    -74.0060 + index * 0.001);
        }
    }

    private void ingest(String vehicleId, String sourceEventId, Instant recordedAt, double lat, double lon) {
        ingestionService.ingest(INGEST_KEY, new TelemetryIngestRequest(
                sourceEventId, vehicleId, recordedAt, lat, lon, 24.0, 90.0, 30, 78));
    }

    private LatestVehicleTelemetry currentState() {
        return telemetryQueryService.findLatestVehicleTelemetry().stream()
                .filter(state -> state.vehicleId().equals("BUS-042"))
                .findFirst()
                .orElseThrow();
    }

    private int processedEventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class);
    }
}
