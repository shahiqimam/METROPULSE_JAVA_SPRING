package com.metropulse.schedule.api;

import com.metropulse.schedule.importer.StagedScheduleImport;
import com.metropulse.schedule.importer.StagedScheduleImportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Uploads a GTFS-style feed, in two steps.
 *
 * <p>Under {@code /api/v1/admin}, so the security rules restrict it to administrators: replacing the
 * schedule changes what every operational calculation is measured against, which is not something a
 * controller does mid-shift.
 *
 * <p>Uploading stages; it does not activate. The two are separate requests because they are separate
 * decisions, and because the interesting one — "yes, put this into service" — should be made by
 * someone who has read what it would do rather than by whoever had the file.
 */
@RestController
@RequestMapping("/api/v1/admin/schedule")
public class ScheduleImportController {

    /** Enough for a small network; a real feed would stream to disk rather than sit in memory. */
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    private final StagedScheduleImportService stagedImportService;

    public ScheduleImportController(StagedScheduleImportService stagedImportService) {
        this.stagedImportService = stagedImportService;
    }

    /** Parses, validates and stores a feed for review. Writes nothing operational. */
    @PostMapping("/imports")
    public StagedScheduleImport stage(
            @RequestPart("files") List<MultipartFile> files,
            @AuthenticationPrincipal Jwt principal
    ) throws IOException {
        return stagedImportService.stage(readFiles(files), actor(principal));
    }

    @GetMapping("/imports")
    public List<StagedScheduleImport> findAll() {
        return stagedImportService.findAll();
    }

    @GetMapping("/imports/{id}")
    public StagedScheduleImport findById(@PathVariable long id) {
        return stagedImportService.findById(id);
    }

    /** Puts a staged feed into service. */
    @PostMapping("/imports/{id}/activate")
    public StagedScheduleImport activate(@PathVariable long id, @AuthenticationPrincipal Jwt principal) {
        return stagedImportService.activate(id, actor(principal));
    }

    /** Sets a staged feed aside without applying it. */
    @PostMapping("/imports/{id}/discard")
    public StagedScheduleImport discard(@PathVariable long id, @AuthenticationPrincipal Jwt principal) {
        return stagedImportService.discard(id, actor(principal));
    }

    private Map<String, byte[]> readFiles(List<MultipartFile> files) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        long total = 0;

        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name == null || name.isBlank()) {
                continue;
            }
            total += file.getSize();
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException(
                        "The feed exceeds the " + (MAX_TOTAL_BYTES / 1024 / 1024) + " MB limit.");
            }
            // Only the base name: an upload must not be able to name a path.
            contents.put(java.nio.file.Paths.get(name).getFileName().toString(), file.getBytes());
        }

        return contents;
    }

    /** Who did it, taken from the token rather than the request body. */
    private String actor(Jwt principal) {
        return principal == null ? "unknown" : principal.getSubject();
    }
}
