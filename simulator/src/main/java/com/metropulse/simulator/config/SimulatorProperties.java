package com.metropulse.simulator.config;

import com.metropulse.simulator.scenario.ScenarioType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "metropulse.simulator")
public class SimulatorProperties {

    private String ingestUrl = "http://localhost:8080/api/v1/telemetry/ingest";
    private String ingestKey = "dev-ingest-key";
    private long seed = 42L;
    private Duration interval = Duration.ofSeconds(2);
    private ScenarioType scenario = ScenarioType.NORMAL_OPERATION;
    private List<String> vehicleIds = List.of("BUS-042", "BUS-101", "BUS-204", "BUS-317");

    public String getIngestUrl() {
        return ingestUrl;
    }

    public void setIngestUrl(String ingestUrl) {
        this.ingestUrl = ingestUrl;
    }

    public String getIngestKey() {
        return ingestKey;
    }

    public void setIngestKey(String ingestKey) {
        this.ingestKey = ingestKey;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public ScenarioType getScenario() {
        return scenario;
    }

    public void setScenario(ScenarioType scenario) {
        this.scenario = scenario;
    }

    public List<String> getVehicleIds() {
        return vehicleIds;
    }

    public void setVehicleIds(List<String> vehicleIds) {
        this.vehicleIds = vehicleIds;
    }
}
