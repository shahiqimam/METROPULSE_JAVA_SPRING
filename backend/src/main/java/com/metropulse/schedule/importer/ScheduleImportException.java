package com.metropulse.schedule.importer;

import java.util.List;

/**
 * Thrown when an import cannot be accepted.
 *
 * <p>Carries every problem found, not just the first. A planner fixing a feed wants the whole list:
 * returning one error at a time turns a ten-minute fix into ten round trips.
 */
public class ScheduleImportException extends RuntimeException {

    private final List<String> problems;

    public ScheduleImportException(List<String> problems) {
        super("Schedule import rejected with " + problems.size() + " problem(s).");
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
