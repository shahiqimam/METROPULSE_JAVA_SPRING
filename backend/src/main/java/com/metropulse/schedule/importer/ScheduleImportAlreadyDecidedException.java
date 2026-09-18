package com.metropulse.schedule.importer;

/**
 * An attempt to decide an import that has already been decided.
 *
 * <p>Refused rather than treated as a no-op: activating the same feed twice is usually two people
 * looking at the same screen, and the second one needs to be told it has already happened rather
 * than left believing they did it.
 */
public class ScheduleImportAlreadyDecidedException extends RuntimeException {

    private final String status;

    public ScheduleImportAlreadyDecidedException(long id, String status) {
        super("Schedule import " + id + " is already " + status + ".");
        this.status = status;
    }

    public String status() {
        return status;
    }
}
