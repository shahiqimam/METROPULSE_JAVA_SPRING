package com.metropulse.simulator.scenario;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The trip a simulated vehicle is running, and where along it the schedule says it should be.
 *
 * <p>Vehicles run scheduled trips rather than looping the shape endlessly. That is what makes
 * schedule adherence measurable at all: without a trip to be measured against, "three minutes late"
 * has no meaning, and any punctuality figure derived from it would be invented.
 *
 * <p>Trip codes are generated to match the seeded service — every ten minutes from 05:00 to 23:00 —
 * so the simulator and the database agree on which trips exist without the simulator querying for
 * them. Both derive the same codes from the same rule; if the seed changes, this must change with it.
 */
public record TripSchedule(String tripCode, int departureSeconds, int durationSeconds) {

    /** Matches the seeded service in V16. */
    private static final int FIRST_DEPARTURE_SECONDS = 18_000;   // 05:00
    private static final int LAST_DEPARTURE_SECONDS = 82_800;    // 23:00
    private static final int HEADWAY_SECONDS = 120;              // every 2 minutes
    private static final int TRIP_DURATION_SECONDS = 390;        // 6.5 minutes end to end

    private static final ZoneId SERVICE_ZONE = ZoneId.of("America/New_York");

    /**
     * Picks the trip a vehicle should be running.
     *
     * <p>Vehicles are offset from each other by their index, so a fleet of four covers four
     * consecutive departures rather than all starting the same trip, and each one comes round again
     * a full fleet's worth of departures later. Outside service hours they are held at the first
     * departure of the day, which is what a depot does.
     */
    public static TripSchedule forVehicle(int vehicleIndex, int fleetSize, Instant now) {
        int serviceSeconds = serviceDaySeconds(now);

        // A vehicle keeps one departure for the whole of that trip and then takes the fleet's next one
        // round, which is how a real block works. Reassigning it to whichever trip happened to be
        // departing would make deviation meaningless: a vehicle halfway along the shape would suddenly
        // be measured against a trip that had not left the terminal yet.
        //
        // Each vehicle's cycle is offset by its own place in the fleet, so it takes a trip at the
        // moment that trip departs. Without the offset every vehicle changes trip at the same instant:
        // the first one is handed its next departure just as it finishes, but the last is handed one
        // while it is still two minutes into a six-minute run, and it never completes a trip at all.
        int elapsed = serviceSeconds - FIRST_DEPARTURE_SECONDS;
        int ownOffset = vehicleIndex * HEADWAY_SECONDS;
        int cycleSeconds = HEADWAY_SECONDS * fleetSize;

        int departureIndex = vehicleIndex;
        if (elapsed >= ownOffset) {
            departureIndex = (elapsed - ownOffset) / cycleSeconds * fleetSize + vehicleIndex;
        }

        int departure = FIRST_DEPARTURE_SECONDS + departureIndex * HEADWAY_SECONDS;
        if (departure > LAST_DEPARTURE_SECONDS) {
            // Wrap to the start of the service day rather than inventing trips that do not exist.
            int wrapped = (departureIndex * HEADWAY_SECONDS)
                    % (LAST_DEPARTURE_SECONDS - FIRST_DEPARTURE_SECONDS + HEADWAY_SECONDS);
            departure = FIRST_DEPARTURE_SECONDS + wrapped;
        }

        return new TripSchedule(tripCode(departure), departure, TRIP_DURATION_SECONDS);
    }

    /** Seconds since the start of the service day, in the network's own timezone. */
    public static int serviceDaySeconds(Instant now) {
        ZonedDateTime local = now.atZone(SERVICE_ZONE);
        return local.getHour() * 3600 + local.getMinute() * 60 + local.getSecond();
    }

    /**
     * Where along the trip the schedule expects the vehicle to be, as a fraction.
     *
     * <p>Clamped to the trip's own window: before it departs the vehicle waits at the origin, and
     * after it should have arrived it holds at the destination rather than running past the end.
     */
    public double scheduledProgress(Instant now) {
        int elapsed = serviceDaySeconds(now) - departureSeconds;
        if (elapsed <= 0) {
            return 0.0;
        }
        if (elapsed >= durationSeconds) {
            return 1.0;
        }
        return (double) elapsed / durationSeconds;
    }

    /** How far into the trip the vehicle is, for logging and for the payload. */
    public Duration elapsed(Instant now) {
        return Duration.ofSeconds(Math.max(0, serviceDaySeconds(now) - departureSeconds));
    }

    private static String tripCode(int departureSeconds) {
        int hours = departureSeconds / 3600;
        int minutes = (departureSeconds % 3600) / 60;
        return String.format("M42-WKD-%02d%02d-EAST", hours, minutes);
    }
}
