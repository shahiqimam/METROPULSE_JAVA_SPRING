package com.metropulse.schedule.importer;

import java.util.List;

/**
 * What activating a staged feed would do to the schedule in service.
 *
 * <p>Counts alone do not answer the question a planner is actually asking. "Twelve routes" is the
 * same number whether the feed adds one to eleven that already exist or replaces every one of them,
 * and those are very different things to approve on a Monday morning. So each kind of record is
 * split into what would be added and what would be changed in place.
 *
 * @param unchangedInFeed routes and stops the platform has that this feed does not mention. They are
 *                        left alone rather than deleted — vehicles are assigned to routes, and
 *                        history is projected onto their geometry — so this is the list of things
 *                        that would quietly survive an import that looks like a replacement.
 */
public record SchedulePreview(
        Change agencies,
        Change routes,
        Change stops,
        Change calendars,
        Change trips,
        int stopTimes,
        int shapePoints,
        List<String> unchangedInFeed,
        List<String> notes
) {

    /**
     * How many records of one kind would be added, and how many already exist under the same key.
     *
     * @param added   records the platform has never seen
     * @param updated records matched on their natural key and written over
     */
    public record Change(int added, int updated) {

        public int total() {
            return added + updated;
        }
    }
}
