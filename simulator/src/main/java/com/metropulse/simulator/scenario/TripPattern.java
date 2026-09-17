package com.metropulse.simulator.scenario;

/**
 * Where a trip is meant to be at any second of its run.
 *
 * <p>The timetable is not a straight line from one terminal to the other. A vehicle drives between
 * stops and stands still at them, and the stops are not evenly spaced along the shape, so scheduled
 * position against time is a staircase rather than a ramp. Interpolating straight from start to end
 * would put a vehicle tens of seconds away from where its own timetable says it should be at every
 * stop but the last - and since stop arrivals are exactly what schedule adherence is measured from,
 * the platform would spend all day reporting a deviation that belonged to the simulator.
 *
 * <p>The offsets here reproduce the seeded pattern in {@code V16__seed_all_day_service.sql}. Both are
 * derived from the same rule - each stop's own distance along the shape, plus a dwell at the ones in
 * between - so if that seed changes, this must change with it.
 */
public final class TripPattern {

    /** Seconds from departure at which the trip reaches each stop. */
    private static final int[] ARRIVAL_OFFSETS = {0, 97, 194, 283, 390};

    /** Seconds from departure at which it leaves each stop again. */
    private static final int[] DEPARTURE_OFFSETS = {30, 127, 224, 313, 390};

    private final double[] stopProgress;

    /**
     * @param stopProgress where each stop sits along the shape, in stop order
     */
    public TripPattern(double[] stopProgress) {
        if (stopProgress.length != ARRIVAL_OFFSETS.length) {
            throw new IllegalArgumentException(
                    "The seeded pattern has %d stops but the shape offers %d; one of them is wrong."
                            .formatted(ARRIVAL_OFFSETS.length, stopProgress.length));
        }
        this.stopProgress = stopProgress.clone();
    }

    /** The scheduled position, as a fraction of the shape, this many seconds into the trip. */
    public double progressAt(long elapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return stopProgress[0];
        }
        if (elapsedSeconds >= ARRIVAL_OFFSETS[ARRIVAL_OFFSETS.length - 1]) {
            return stopProgress[stopProgress.length - 1];
        }

        for (int stop = 0; stop < stopProgress.length - 1; stop++) {
            if (elapsedSeconds <= DEPARTURE_OFFSETS[stop]) {
                // Standing at this stop: the schedule expects no movement at all.
                return stopProgress[stop];
            }
            if (elapsedSeconds < ARRIVAL_OFFSETS[stop + 1]) {
                long leg = ARRIVAL_OFFSETS[stop + 1] - DEPARTURE_OFFSETS[stop];
                double travelled = (double) (elapsedSeconds - DEPARTURE_OFFSETS[stop]) / leg;
                return stopProgress[stop] + travelled * (stopProgress[stop + 1] - stopProgress[stop]);
            }
        }

        return stopProgress[stopProgress.length - 1];
    }

    /** Total driving time in the pattern, used to derive the speed the timetable implies. */
    public static int runningSeconds() {
        int running = 0;
        for (int stop = 0; stop < ARRIVAL_OFFSETS.length - 1; stop++) {
            running += ARRIVAL_OFFSETS[stop + 1] - DEPARTURE_OFFSETS[stop];
        }
        return running;
    }
}
