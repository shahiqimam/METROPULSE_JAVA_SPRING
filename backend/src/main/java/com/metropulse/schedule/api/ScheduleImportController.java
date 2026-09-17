package com.metropulse.schedule.api;

import com.metropulse.schedule.importer.ScheduleImportResult;
import com.metropulse.schedule.importer.ScheduleImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uploads a GTFS-style feed.
 *
 * <p>Under {@code /api/v1/admin}, so the security rules restrict it to administrators: replacing the
 * schedule changes what every operational calculation is measured against, which is not something a
 * controller does mid-shift.
 */
@RestController
@RequestMapping("/api/v1/admin/schedule")
public class ScheduleImportController {

    /** Enough for a small network; a real feed would stream to disk rather than sit in memory. */
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    private final ScheduleImportService scheduleImportService;

    public ScheduleImportController(ScheduleImportService scheduleImportService) {
        this.scheduleImportService = scheduleImportService;
    }

    @PostMapping("/import")
    public ScheduleImportResult importFeed(@RequestPart("files") List<MultipartFile> files) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        long total = 0;

        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name == null || name.isBlank()) {
                continue;
            }
            total += file.getSize();
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("The feed exceeds the " + (MAX_TOTAL_BYTES / 1024 / 1024) + " MB limit.");
            }
            // Only the base name: an upload must not be able to name a path.
            contents.put(java.nio.file.Paths.get(name).getFileName().toString(), file.getBytes());
        }

        return scheduleImportService.importFeed(contents);
    }
}
