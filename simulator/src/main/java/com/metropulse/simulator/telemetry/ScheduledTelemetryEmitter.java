package com.metropulse.simulator.telemetry;

import com.metropulse.simulator.config.SimulatorProperties;
import com.metropulse.simulator.scenario.FleetSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Posts one round of synthetic telemetry per tick.
 *
 * <p>Movement and scenario behaviour live in {@link FleetSimulator}; this class only decides when a
 * tick happens and how the result reaches the ingest API.
 */
@Component
public class ScheduledTelemetryEmitter {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTelemetryEmitter.class);

    private final SimulatorProperties properties;
    private final FleetSimulator fleetSimulator;
    private final WebClient webClient;

    public ScheduledTelemetryEmitter(
            SimulatorProperties properties,
            FleetSimulator fleetSimulator,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.fleetSimulator = fleetSimulator;
        this.webClient = webClientBuilder.build();
    }

    @Scheduled(fixedDelayString = "${metropulse.simulator.interval:PT2S}", initialDelayString = "${metropulse.simulator.initial-delay:PT5S}")
    public void emit() {
        List<TelemetryIngestPayload> payloads = fleetSimulator.tick(Instant.now());

        if (payloads.isEmpty()) {
            log.debug("Tick {} produced no telemetry; every vehicle is silent under scenario {}.",
                    fleetSimulator.tickNumber(), properties.getScenario());
            return;
        }

        payloads.forEach(this::publish);
    }

    private void publish(TelemetryIngestPayload payload) {
        try {
            TelemetryIngestResponse response = webClient.post()
                    .uri(properties.getIngestUrl())
                    .header("X-Ingest-Key", properties.getIngestKey())
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(TelemetryIngestResponse.class)
                    .block(Duration.ofSeconds(5));

            if (response != null) {
                log.info("Telemetry event {} for {} returned {}",
                        response.sourceEventId(), payload.vehicleId(), response.status());
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to publish telemetry event {} to {}",
                    payload.sourceEventId(), properties.getIngestUrl(), ex);
        }
    }
}
