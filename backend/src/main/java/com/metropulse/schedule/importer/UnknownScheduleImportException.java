package com.metropulse.schedule.importer;

/** A schedule import that does not exist. */
public class UnknownScheduleImportException extends RuntimeException {

    public UnknownScheduleImportException(long id) {
        super("No schedule import with id " + id + ".");
    }
}
