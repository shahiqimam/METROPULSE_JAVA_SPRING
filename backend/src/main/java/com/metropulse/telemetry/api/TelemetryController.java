package com.metropulse.telemetry.api;

import com.metropulse.telemetry.application.TelemetryIngestionService;
import com.metropulse.telemetry.domain.TelemetryIngestStatus;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetryIngestionService telemetryIngestionService;
    private final TelemetryQueryService telemetryQueryService;

    public TelemetryController(
            TelemetryIngestionService telemetryIngestionService,
            TelemetryQueryService telemetryQueryService
    ) {
        this.telemetryIngestionService = telemetryIngestionService;
        this.telemetryQueryService = telemetryQueryService;
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

    @GetMapping("/vehicles/latest")
    public List<LatestVehicleTelemetry> latestVehicleTelemetry() {
        return telemetryQueryService.findLatestVehicleTelemetry();
    }

    public record TelemetryIngestResponse(String sourceEventId, TelemetryIngestStatus status) {
    }
}
