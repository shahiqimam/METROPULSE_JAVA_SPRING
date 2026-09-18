package com.metropulse.schedule.importer;

import java.time.Instant;
import java.util.List;

/**
 * A feed that has been uploaded and checked, and is waiting for someone to decide.
 *
 * @param status        STAGED until a planner activates or discards it
 * @param preview       what the comparison said when it was staged
 * @param fileNames     what was uploaded, so the record says which feed this was
 * @param result        what activation actually did, once it has happened
 */
public record StagedScheduleImport(
        long id,
        String status,
        String uploadedBy,
        Instant uploadedAt,
        String activatedBy,
        Instant activatedAt,
        String discardedBy,
        Instant discardedAt,
        SchedulePreview preview,
        List<String> fileNames,
        ScheduleImportResult result
) {

    public static final String STAGED = "STAGED";
    public static final String ACTIVATED = "ACTIVATED";
    public static final String DISCARDED = "DISCARDED";

    public boolean isStaged() {
        return STAGED.equals(status);
    }
}
