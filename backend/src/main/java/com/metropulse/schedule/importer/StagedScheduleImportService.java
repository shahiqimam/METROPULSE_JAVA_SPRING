package com.metropulse.schedule.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staging and activation of schedule feeds.
 *
 * <h2>Why an upload is not an activation</h2>
 *
 * <p>The schedule is what every operational calculation is measured against. Route progress, headway,
 * schedule deviation and punctuality all compare a vehicle against it, so replacing it changes the
 * meaning of every number a controller is looking at — including, retrospectively, the ones they were
 * looking at a minute ago. That is not something to do as a side effect of a file upload succeeding.
 *
 * <p>So an upload parses, validates, and reports what it would change. Nothing operational is written.
 * A planner reads the preview and decides.
 *
 * <h2>Why the files are kept rather than the parsed feed</h2>
 *
 * <p>Activation re-parses and re-validates the uploaded bytes. Keeping the parsed result would be
 * faster and would mean activating something nobody uploaded: a transformation made by the version of
 * the parser that happened to be running at staging time. Re-parsing also means a feed staged against
 * one state of the database is re-checked against the state it is actually applied to.
 *
 * <h2>What the preview does not promise</h2>
 *
 * <p>It is a comparison at a moment. Between staging and activation the schedule can change — another
 * import, or the seed on a fresh environment — so activation reports what it did rather than
 * confirming what the preview said. Where they disagree, the result is the truth.
 */
@Service
public class StagedScheduleImportService {

    private static final Logger log = LoggerFactory.getLogger(StagedScheduleImportService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ScheduleFeedParser parser;
    private final ScheduleFeedValidator validator;
    private final SchedulePreviewBuilder previewBuilder;
    private final ScheduleImportService importService;
    private final ObjectMapper objectMapper;

    public StagedScheduleImportService(
            JdbcTemplate jdbcTemplate,
            ScheduleFeedParser parser,
            ScheduleFeedValidator validator,
            SchedulePreviewBuilder previewBuilder,
            ScheduleImportService importService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.parser = parser;
        this.validator = validator;
        this.previewBuilder = previewBuilder;
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    /**
     * Parses, validates and stores a feed for review.
     *
     * @throws ScheduleImportException listing every problem, with nothing stored
     */
    @Transactional
    public StagedScheduleImport stage(Map<String, byte[]> files, String uploadedBy) {
        ScheduleFeed feed = parser.parse(files);
        validator.validate(feed);

        SchedulePreview preview = previewBuilder.describe(feed);

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO schedule_import (status, uploaded_by, preview)
                VALUES (?, ?, CAST(? AS jsonb))
                RETURNING id
                """, Long.class, StagedScheduleImport.STAGED, uploadedBy, toJson(preview));

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO schedule_import_file (import_id, file_name, content)
                    VALUES (?, ?, ?)
                    """, id, file.getKey(), file.getValue());
        }

        log.info("Staged schedule import {} from {} ({} routes, {} trips).",
                id, uploadedBy, preview.routes().total(), preview.trips().total());

        return findById(id);
    }

    /**
     * Applies a staged feed.
     *
     * <p>One transaction: the schedule moves from one complete state to another, and the import is
     * marked activated in the same breath. A feed that fails validation on the way in leaves the
     * schedule and the import untouched, ready to be looked at again.
     */
    @Transactional
    public StagedScheduleImport activate(long id, String activatedBy) {
        StagedScheduleImport staged = findById(id);
        if (!staged.isStaged()) {
            throw new ScheduleImportAlreadyDecidedException(id, staged.status());
        }

        ScheduleImportResult result = importService.importFeed(filesOf(id));

        jdbcTemplate.update("""
                UPDATE schedule_import
                SET status = ?, activated_by = ?, activated_at = now()
                WHERE id = ? AND status = ?
                """, StagedScheduleImport.ACTIVATED, activatedBy, id, StagedScheduleImport.STAGED);

        jdbcTemplate.update("""
                UPDATE schedule_import
                SET result = CAST(? AS jsonb)
                WHERE id = ?
                """, toJson(result), id);

        log.info("Activated schedule import {} by {}.", id, activatedBy);
        return findById(id);
    }

    /** Sets a staged feed aside. The upload is kept, because "what did we decide not to do" is a question. */
    @Transactional
    public StagedScheduleImport discard(long id, String discardedBy) {
        StagedScheduleImport staged = findById(id);
        if (!staged.isStaged()) {
            throw new ScheduleImportAlreadyDecidedException(id, staged.status());
        }

        jdbcTemplate.update("""
                UPDATE schedule_import
                SET status = ?, discarded_by = ?, discarded_at = now()
                WHERE id = ? AND status = ?
                """, StagedScheduleImport.DISCARDED, discardedBy, id, StagedScheduleImport.STAGED);

        return findById(id);
    }

    public List<StagedScheduleImport> findAll() {
        return jdbcTemplate.query("""
                SELECT id, status, uploaded_by, uploaded_at, activated_by, activated_at,
                       discarded_by, discarded_at, preview::text AS preview, result::text AS result,
                       (SELECT COALESCE(string_agg(file_name, ',' ORDER BY file_name), '')
                        FROM schedule_import_file f WHERE f.import_id = schedule_import.id) AS file_names
                FROM schedule_import
                ORDER BY uploaded_at DESC, id DESC
                """, this::toImport);
    }

    public StagedScheduleImport findById(long id) {
        return jdbcTemplate.query("""
                SELECT id, status, uploaded_by, uploaded_at, activated_by, activated_at,
                       discarded_by, discarded_at, preview::text AS preview, result::text AS result,
                       (SELECT COALESCE(string_agg(file_name, ',' ORDER BY file_name), '')
                        FROM schedule_import_file f WHERE f.import_id = schedule_import.id) AS file_names
                FROM schedule_import
                WHERE id = ?
                """, this::toImport, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UnknownScheduleImportException(id));
    }

    private Map<String, byte[]> filesOf(long id) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT file_name, content FROM schedule_import_file
                WHERE import_id = ?
                ORDER BY file_name
                """,
                rs -> {
                    files.put(rs.getString("file_name"), rs.getBytes("content"));
                },
                id);
        return files;
    }

    private StagedScheduleImport toImport(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String fileNames = rs.getString("file_names");

        return new StagedScheduleImport(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getString("uploaded_by"),
                toInstant(rs.getTimestamp("uploaded_at")),
                rs.getString("activated_by"),
                toInstant(rs.getTimestamp("activated_at")),
                rs.getString("discarded_by"),
                toInstant(rs.getTimestamp("discarded_at")),
                read(rs.getString("preview"), SchedulePreview.class),
                fileNames == null || fileNames.isBlank() ? List.of() : List.of(fileNames.split(",")),
                read(rs.getString("result"), ScheduleImportResult.class));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A preview that cannot be serialised cannot be stored.", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored " + type.getSimpleName() + " could not be read back.", ex);
        }
    }
}
