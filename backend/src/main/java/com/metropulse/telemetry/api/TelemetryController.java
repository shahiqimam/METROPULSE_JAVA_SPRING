package com.metropulse.telemetry.api;

import com.metropulse.telemetry.application.TelemetryIngestionService;
import com.metropulse.telemetry.domain.TelemetryIngestStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetryIngestionService telemetryIngestionService;

    public TelemetryController(TelemetryIngestionService telemetryIngestionService) {
        this.telemetryIngestionService = telemetryIngestionService;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TelemetryIngestResponse ingest(
            @RequestHeader("X-Ingest-Key") String ingestKey,
            @Valid @RequestBody TelemetryIngestRequest request
    ) {
        var result = telemetryIngestionService.ingest(ingestKey, request);
        return new TelemetryIngestResponse(result.sourceEventId(), result.status());
    }

    public record TelemetryIngestResponse(String sourceEventId, TelemetryIngestStatus status) {
    }
}
