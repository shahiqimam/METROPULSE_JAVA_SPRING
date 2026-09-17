package com.metropulse.operations;

import com.metropulse.operations.schedule.StopArrivalDetector;
import com.metropulse.support.OutboxPipeline;
import com.metropulse.support.PostgisIntegrationTest;
import com.metropulse.telemetry.api.TelemetryIngestRequest;
import com.metropulse.telemetry.application.TelemetryIngestionService;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stop arrival detection and schedule deviation.
 *
 * <p>Observations are placed at the seeded M42 stops at controlled times, so each test states exactly
 * one situation: arriving on time, arriving late, driving past without stopping, and so on.
 *
 * <p>Times are built in the network's own timezone, because a service day is local. Using UTC here
 * would make every deviation wrong by the offset and the tests would still pass on a machine set to
 * UTC, which is the kind of bug that only appears in another timezone.
 */
class StopArrivalIntegrationTest extends PostgisIntegrationTest {

    private static final String INGEST_KEY = "test-ingest-key";
    private static final String VEHICLE = "BUS-042";
    private static final String TRIP = "M42-WKD-0700-EAST";
    private static final ZoneId SERVICE_ZONE = ZoneId.of("America/New_York");

    /** The first stop of the seeded pattern, scheduled for 07:00:00. */
    private static final double FIRST_STOP_LAT = 40.7128;
    private static final double FIRST_STOP_LON = -74.0060;

    /** The second stop, scheduled for 07:01:37 - 97 seconds into the trip. */
    private static final double SECOND_STOP_LAT = 40.7140;
    private static final double SECOND_STOP_LON = -74.0020;

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private TelemetryQueryService queryService;

    @Autowired
    private OutboxPipeline outboxPipeline;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM stop_arrival");
        jdbcTemplate.update("DELETE FROM vehicle_current_state");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM vehicle_telemetry");
    }

    @Test
    void callingAtAStopOnTimeRecordsAZeroDeviation() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);

        assertThat(arrivalCount()).isEqualTo(1);
        assertThat(deviationOfLastArrival()).isZero();
        assertThat(currentState().scheduleDeviationSeconds()).isZero();
    }

    @Test
    void arrivingLateRecordsHowLate() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:03:20"), 0.0);

        // Planned 07:00:00, actual 07:03:20 - two hundred seconds late, positive by convention.
        assertThat(deviationOfLastArrival()).isEqualTo(200);
        assertThat(currentState().scheduleDeviationSeconds()).isEqualTo(200);
    }

    @Test
    void arrivingEarlyRecordsANegativeDeviation() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("06:58:00"), 0.0);

        assertThat(deviationOfLastArrival()).isEqualTo(-120);
    }

    @Test
    void drivingPastAStopWithoutSlowingDownIsNotAnArrival() {
        // At the stop, but at road speed: this is a vehicle passing, not calling.
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 45.0);

        assertThat(arrivalCount()).isZero();
        assertThat(currentState().scheduleDeviationSeconds()).isNull();
    }

    @Test
    void beingSlowNearbyButNotAtAStopIsNotAnArrival() {
        // Roughly 500 m from the stop, crawling: stuck in traffic, not serving the stop.
        arriveAt(FIRST_STOP_LAT + 0.0045, FIRST_STOP_LON, at("07:00:00"), 3.0);

        assertThat(arrivalCount()).isZero();
    }

    @Test
    void speedExactlyAtTheThresholdStillCounts() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), StopArrivalDetector.ARRIVAL_SPEED_KPH);

        assertThat(arrivalCount()).isEqualTo(1);
    }

    @Test
    void theSameStopIsRecordedOnceHoweverManyObservationsArrive() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:05"), 0.0);
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:10"), 0.0);

        // A vehicle sitting at a stop reports many times; that is one call.
        assertThat(arrivalCount()).isEqualTo(1);
        assertThat(deviationOfLastArrival()).as("the first observation is the arrival").isZero();
    }

    @Test
    void leavingTheStopClosesTheDwell() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:40"), 25.0);

        Integer dwell = jdbcTemplate.queryForObject(
                "SELECT dwell_seconds FROM stop_arrival ORDER BY id DESC LIMIT 1", Integer.class);

        assertThat(dwell).isEqualTo(40);
    }

    @Test
    void callingAtSeveralStopsRecordsEachOne() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);
        arriveAt(SECOND_STOP_LAT, SECOND_STOP_LON, at("07:02:37"), 0.0);

        assertThat(arrivalCount()).isEqualTo(2);
        // The current figure follows the most recent call: a minute after the planned 07:01:37.
        assertThat(currentState().scheduleDeviationSeconds()).isEqualTo(60);
    }

    @Test
    void theNextStopIsTheOneAfterTheLastCall() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);

        assertThat(currentState().nextStopName()).isEqualTo("Hudson Exchange");
    }

    @Test
    void aVehicleReportingNoTripIsNotMeasured() {
        ingest(null, FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);

        // Out of service: it has a position, but nothing to be measured against.
        assertThat(arrivalCount()).isZero();
        assertThat(currentState().scheduleDeviationSeconds()).isNull();
        assertThat(currentState().tripCode()).isNull();
    }

    @Test
    void theTripIsCarriedOntoCurrentState() {
        arriveAt(FIRST_STOP_LAT, FIRST_STOP_LON, at("07:00:00"), 0.0);

        assertThat(currentState().tripCode()).isEqualTo(TRIP);
    }

    private Instant at(String localTime) {
        // A fixed weekday so the service calendar applies, in the network's own timezone.
        return LocalDateTime.of(LocalDate.of(2026, 9, 16), LocalTime.parse(localTime))
                .atZone(SERVICE_ZONE)
                .toInstant();
    }

    private void arriveAt(double latitude, double longitude, Instant when, double speedKph) {
        ingest(TRIP, latitude, longitude, when, speedKph);
    }

    private void ingest(String tripCode, double latitude, double longitude, Instant when, double speedKph) {
        ingestionService.ingest(INGEST_KEY, new TelemetryIngestRequest(
                "arrival-" + System.nanoTime(), VEHICLE, tripCode, when,
                latitude, longitude, speedKph, 90.0, 20, 80));
        outboxPipeline.drain();
    }

    private LatestVehicleTelemetry currentState() {
        return queryService.findLatestVehicleTelemetry().stream()
                .filter(state -> state.vehicleId().equals(VEHICLE))
                .findFirst()
                .orElseThrow();
    }

    private int arrivalCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stop_arrival", Integer.class);
    }

    private int deviationOfLastArrival() {
        return jdbcTemplate.queryForObject(
                "SELECT deviation_seconds FROM stop_arrival ORDER BY stop_sequence DESC LIMIT 1", Integer.class);
    }
}
