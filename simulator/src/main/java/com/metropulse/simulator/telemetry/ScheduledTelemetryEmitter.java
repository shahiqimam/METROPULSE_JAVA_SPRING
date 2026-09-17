package com.metropulse.simulator.telemetry;

import com.metropulse.simulator.config.SimulatorProperties;
import com.metropulse.simulator.scenario.VehicleScenarioState;
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
    private final String runId;
    private final AtomicLong sequence = new AtomicLong();

    public ScheduledTelemetryEmitter(SimulatorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.random = new Random(properties.getSeed());
        this.runId = Long.toString(Instant.now().toEpochMilli());
    }

    @Scheduled(fixedDelayString = "${metropulse.simulator.interval:PT2S}", initialDelayString = "${metropulse.simulator.initial-delay:PT5S}")
    public void emit() {
        List<String> vehicleIds = properties.getVehicleIds();
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            log.warn("Simulator has no configured vehicle IDs; skipping telemetry tick.");
            return;
        }

        long tickNumber = sequence.incrementAndGet();
        for (int vehicleIndex = 0; vehicleIndex < vehicleIds.size(); vehicleIndex++) {
            TelemetryIngestPayload payload = nextPayload(tickNumber, vehicleIndex, vehicleIds.get(vehicleIndex));
            publish(payload);
        }
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
                log.info("Telemetry event {} for {} returned {}", response.sourceEventId(), payload.vehicleId(), response.status());
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to publish telemetry event {} to {}", payload.sourceEventId(), properties.getIngestUrl(), ex);
        }
    }

    private TelemetryIngestPayload nextPayload(long tickNumber, int vehicleIndex, String vehicleId) {
        VehicleScenarioState state = scenarioState(tickNumber, vehicleIndex);
        double[] point = ROUTE.get(state.routePointIndex());
        double jitter = (random.nextDouble() - 0.5) / 10000.0;

        return new TelemetryIngestPayload(
                "sim-" + runId + "-" + vehicleId + "-" + tickNumber,
                vehicleId,
                Instant.now(),
                point[0] + jitter,
                point[1] - jitter,
                state.speedKph(),
                state.headingDegrees(),
                state.occupancyEstimate(),
                state.batteryPercent()
        );
    }

    private VehicleScenarioState scenarioState(long tickNumber, int vehicleIndex) {
        int normalIndex = Math.floorMod((int) tickNumber + vehicleIndex, ROUTE.size());
        return switch (properties.getScenario()) {
            case BUNCHING -> new VehicleScenarioState(
                    Math.floorMod((int) tickNumber + Math.min(vehicleIndex, 1), ROUTE.size()),
                    speed(12.0, 15.0),
                    88.0,
                    42 + random.nextInt(28),
                    battery(58, vehicleIndex, tickNumber)
            );
            case ROUTE_DEVIATION -> new VehicleScenarioState(
                    vehicleIndex == 0 ? Math.floorMod((int) tickNumber + 2, ROUTE.size()) : normalIndex,
                    speed(18.0, 12.0),
                    vehicleIndex == 0 ? 135.0 : 82.0,
                    20 + random.nextInt(35),
                    battery(64, vehicleIndex, tickNumber)
            );
            case TELEMETRY_LOSS -> new VehicleScenarioState(
                    normalIndex,
                    vehicleIndex == 2 && tickNumber % 4 == 0 ? 0.0 : speed(20.0, 16.0),
                    84.0,
                    18 + random.nextInt(35),
                    battery(62, vehicleIndex, tickNumber)
            );
            case LONG_DWELL -> new VehicleScenarioState(
                    vehicleIndex == 1 ? 2 : normalIndex,
                    vehicleIndex == 1 ? speed(0.0, 3.0) : speed(18.0, 16.0),
                    91.0,
                    vehicleIndex == 1 ? 70 + random.nextInt(12) : 20 + random.nextInt(35),
                    battery(60, vehicleIndex, tickNumber)
            );
            case EV_LOW_BATTERY -> new VehicleScenarioState(
                    normalIndex,
                    speed(16.0, 14.0),
                    86.0,
                    18 + random.nextInt(35),
                    vehicleIndex == 0 ? battery(18, vehicleIndex, tickNumber) : battery(56, vehicleIndex, tickNumber)
            );
            case MULTI_INCIDENT -> new VehicleScenarioState(
                    Math.floorMod((int) tickNumber + vehicleIndex / 2, ROUTE.size()),
                    vehicleIndex < 2 ? speed(4.0, 8.0) : speed(16.0, 14.0),
                    76.0,
                    30 + random.nextInt(48),
                    battery(42, vehicleIndex, tickNumber)
            );
            case RECOVERY -> new VehicleScenarioState(
                    normalIndex,
                    speed(24.0, 18.0),
                    80.0,
                    14 + random.nextInt(30),
                    battery(68, vehicleIndex, tickNumber)
            );
            case NORMAL_OPERATION -> new VehicleScenarioState(
                    normalIndex,
                    speed(22.0, 18.0),
                    80.0 + random.nextDouble() * 40.0,
                    18 + random.nextInt(35),
                    battery(70, vehicleIndex, tickNumber)
            );
        };
    }

    private double speed(double base, double spread) {
        return base + random.nextDouble() * spread;
    }

    private int battery(int base, int vehicleIndex, long tickNumber) {
        int drain = (int) Math.floorMod(tickNumber + vehicleIndex * 7L, 18);
        return Math.max(5, Math.min(96, base + 18 - drain));
    }
}
