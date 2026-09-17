package com.metropulse.operations.schedule;

import com.metropulse.operations.projection.VehicleObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Decides when a vehicle has called at a stop, and how late it was.
 *
 * <h2>Why this is harder than "is it near the stop"</h2>
 *
 * <p>A vehicle passing a stop without serving it, a vehicle stuck in traffic beside a stop, and a
 * vehicle actually calling at one all look similar from a position alone. The test used here is
 * deliberately conservative:
 *
 * <ul>
 *   <li>within {@value #ARRIVAL_RADIUS_METERS} m of the stop,</li>
 *   <li>at or below {@value #ARRIVAL_SPEED_KPH} km/h, and</li>
 *   <li>the stop belongs to the trip the vehicle says it is running, and</li>
 *   <li>it has not already been recorded for that trip today.</li>
 * </ul>
 *
 * <p>Requiring both proximity and low speed is what stops one noisy GPS sample from inventing an
 * arrival at a stop the vehicle drove past. The trade is the opposite error: a vehicle that serves a
 * stop without slowing below the threshold is missed, which understates how many calls were made
 * rather than overstating punctuality.
 *
 * <h2>Deviation</h2>
 *
 * <p>Actual arrival minus planned arrival, in seconds, positive for late. Both are expressed in
 * service-day seconds, so a trip that runs past midnight is compared against the day it belongs to
 * rather than the calendar date it happens on.
 */
@Component
public class StopArrivalDetector {

    /** MetroPulse project thresholds, not transit-industry standards. */
    public static final double ARRIVAL_RADIUS_METERS = 40.0;
    public static final double ARRIVAL_SPEED_KPH = 10.0;

    private static final Logger log = LoggerFactory.getLogger(StopArrivalDetector.class);

    /** The network's own timezone; service days are local, not UTC. */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("America/New_York");

    private final JdbcTemplate jdbcTemplate;

    public StopArrivalDetector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records an arrival if this observation is one, and returns the vehicle's schedule deviation.
     *
     * @return deviation in seconds against the trip's schedule, or null when it cannot be measured
     */
    public ScheduleAdherence apply(VehicleObservation observation, Long tripRowId, Long vehicleRowId) {
        if (tripRowId == null) {
            // Out of service, or a vehicle that does not report its trip: nothing to measure against.
            return ScheduleAdherence.notMeasurable();
        }

        ZonedDateTime local = observation.recordedAt().atZone(SERVICE_ZONE);
        LocalDate serviceDate = local.toLocalDate();
        int observedSeconds = local.getHour() * 3600 + local.getMinute() * 60 + local.getSecond();

        recordArrivalIfAtStop(observation, tripRowId, vehicleRowId, serviceDate, observedSeconds);
        recordDepartureIfLeft(observation, tripRowId, serviceDate);

        return currentAdherence(tripRowId, vehicleRowId, serviceDate, observedSeconds);
    }

    /**
     * Writes the arrival when the vehicle is at a stop it has not yet called at on this trip.
     *
     * <p>The insert carries the conditions rather than checking first and inserting after: the unique
     * constraint on (trip, stop, service date) is what actually guarantees one row, and a check-then-
     * insert would let a redelivered event slip a second one in.
     */
    private void recordArrivalIfAtStop(
            VehicleObservation observation,
            Long tripRowId,
            Long vehicleRowId,
            LocalDate serviceDate,
            int observedSeconds
    ) {
        if (observation.speedKph() > ARRIVAL_SPEED_KPH) {
            return;
        }

        try {
            int inserted = jdbcTemplate.update("""
                    INSERT INTO stop_arrival (
                        trip_id, stop_id, vehicle_id, stop_sequence, service_date,
                        arrived_at, planned_arrival_seconds, actual_arrival_seconds, deviation_seconds
                    )
                    SELECT
                        st.trip_id,
                        st.stop_id,
                        ?,
                        st.stop_sequence,
                        CAST(? AS date),
                        ?,
                        st.planned_arrival_seconds,
                        ?,
                        ? - st.planned_arrival_seconds
                    FROM stop_time st
                    JOIN stop s ON s.id = st.stop_id
                    WHERE st.trip_id = ?
                      AND ST_DWithin(
                              s.location::geography,
                              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                              ?)
                    ORDER BY st.stop_sequence
                    LIMIT 1
                    ON CONFLICT (trip_id, stop_id, service_date) DO NOTHING
                    """,
                    vehicleRowId,
                    serviceDate,
                    at(observation.recordedAt()),
                    observedSeconds,
                    observedSeconds,
                    tripRowId,
                    observation.longitude(),
                    observation.latitude(),
                    ARRIVAL_RADIUS_METERS);

            if (inserted > 0) {
                log.debug("Recorded a stop arrival for vehicle {} on trip {}.",
                        observation.vehicleId(), tripRowId);
            }
        } catch (DuplicateKeyException ex) {
            // Two observations inside the radius in the same instant; the first one wins.
            log.trace("Duplicate stop arrival ignored for trip {}.", tripRowId);
        }
    }

    /**
     * Closes the dwell once the vehicle has left the stop it was at.
     *
     * <p>Departure is the absence of the arrival condition rather than an event of its own: the
     * vehicle is no longer near the stop, or is moving again.
     */
    private void recordDepartureIfLeft(VehicleObservation observation, Long tripRowId, LocalDate serviceDate) {
        jdbcTemplate.update("""
                UPDATE stop_arrival sa
                SET departed_at = ?,
                    dwell_seconds = GREATEST(0, EXTRACT(EPOCH FROM (? - sa.arrived_at))::int)
                FROM stop s
                WHERE sa.stop_id = s.id
                  AND sa.trip_id = ?
                  AND sa.service_date = CAST(? AS date)
                  AND sa.departed_at IS NULL
                  AND (
                        NOT ST_DWithin(
                                s.location::geography,
                                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                ?)
                        OR ? > ?
                  )
                """,
                at(observation.recordedAt()),
                at(observation.recordedAt()),
                tripRowId,
                serviceDate,
                observation.longitude(),
                observation.latitude(),
                ARRIVAL_RADIUS_METERS,
                observation.speedKph(),
                ARRIVAL_SPEED_KPH);
    }

    /**
     * The vehicle's current standing against its schedule.
     *
     * <p>Taken from the most recent call it actually made. Between stops the figure is carried
     * forward rather than interpolated: an interpolated deviation is a guess about a vehicle's
     * progress between two points, and labelling a guess with the same word as a measurement is how
     * punctuality figures stop meaning anything.
     */
    private ScheduleAdherence currentAdherence(
            Long tripRowId,
            Long vehicleRowId,
            LocalDate serviceDate,
            int observedSeconds
    ) {
        List<ScheduleAdherence> rows = jdbcTemplate.query("""
                SELECT
                    sa.deviation_seconds,
                    (
                        SELECT st.stop_id
                        FROM stop_time st
                        WHERE st.trip_id = sa.trip_id
                          AND st.stop_sequence > sa.stop_sequence
                        ORDER BY st.stop_sequence
                        LIMIT 1
                    ) AS next_stop_id
                FROM stop_arrival sa
                WHERE sa.trip_id = ?
                  AND sa.vehicle_id = ?
                  AND sa.service_date = CAST(? AS date)
                ORDER BY sa.stop_sequence DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new ScheduleAdherence(
                        rs.getInt("deviation_seconds"),
                        (Long) rs.getObject("next_stop_id"),
                        true),
                tripRowId,
                vehicleRowId,
                serviceDate);

        if (!rows.isEmpty()) {
            return rows.getFirst();
        }

        // No call yet on this trip: the first stop is next, and there is nothing to measure.
        Long firstStop = jdbcTemplate.query("""
                SELECT stop_id FROM stop_time WHERE trip_id = ? ORDER BY stop_sequence LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("stop_id") : null,
                tripRowId);

        return new ScheduleAdherence(null, firstStop, false);
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * How a vehicle stands against its schedule.
     *
     * @param deviationSeconds positive is late, negative is early, null when no call has been made yet
     * @param measured         false when the vehicle has not yet reached a stop on this trip
     */
    public record ScheduleAdherence(Integer deviationSeconds, Long nextStopId, boolean measured) {

        public static ScheduleAdherence notMeasurable() {
            return new ScheduleAdherence(null, null, false);
        }
    }
}
