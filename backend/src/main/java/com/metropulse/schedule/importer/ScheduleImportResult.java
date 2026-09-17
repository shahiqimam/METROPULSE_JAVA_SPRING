package com.metropulse.schedule.importer;

/** What an accepted import changed. */
public record ScheduleImportResult(
        int agencies,
        int routes,
        int stops,
        int calendars,
        int trips,
        int stopTimes,
        int shapePoints
) {
}
