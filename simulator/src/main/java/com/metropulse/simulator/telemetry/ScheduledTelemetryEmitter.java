package com.metropulse.simulator.telemetry;

import com.metropulse.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ScheduledTelemetryEmitter {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTelemetryEmitter.class);

    private static final List<double[]> ROUTE = List.of(
            new double[]{40.7128, -74.0060},
            new double[]{40.7136, -74.0017},
            new double[]{40.7151, -73.9982},
            new double[]{40.7170, -73.9955},
            new double[]{40.7193, -73.9931}
    );

    private final SimulatorProperties properties;
    private final WebClient webClient;
    private final Random random;
    private final AtomicLong sequence = new AtomicLong();

    public ScheduledTelemetryEmitter(SimulatorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.random = new Random(properties.getSeed());
    }

    @Scheduled(fixedDelayString = "${metropulse.simulator.interval:PT2S}", initialDelayString = "${metropulse.simulator.initial-delay:PT5S}")
    public void emit() {
        long eventNumber = sequence.incrementAndGet();
        TelemetryIngestPayload payload = nextPayload(eventNumber);

        try {
            TelemetryIngestResponse response = webClient.post()
                    .uri(properties.getIngestUrl())
                    .header("X-Ingest-Key", properties.getIngestKey())
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(TelemetryIngestResponse.class)
                    .block(Duration.ofSeconds(5));

            if (response != null) {
                log.info("Telemetry event {} for {} returned {}", response.sourceEventId(), payload.vehicleId(), response.status());
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to publish telemetry event {} to {}", payload.sourceEventId(), properties.getIngestUrl(), ex);
        }
    }

    private TelemetryIngestPayload nextPayload(long eventNumber) {
        List<String> vehicleIds = properties.getVehicleIds();
        String vehicleId = vehicleIds.get((int) ((eventNumber - 1) % vehicleIds.size()));
        double[] point = ROUTE.get((int) ((eventNumber - 1) % ROUTE.size()));
        double jitter = (random.nextDouble() - 0.5) / 10000.0;

        return new TelemetryIngestPayload(
                "sim-" + vehicleId + "-" + eventNumber,
                vehicleId,
                Instant.now(),
                point[0] + jitter,
                point[1] - jitter,
                22.0 + random.nextDouble() * 18.0,
                80.0 + random.nextDouble() * 40.0,
                18 + random.nextInt(35),
                70 + random.nextInt(25)
        );
    }
}
