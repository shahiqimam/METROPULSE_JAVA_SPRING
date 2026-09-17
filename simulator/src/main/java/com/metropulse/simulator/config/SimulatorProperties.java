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

    /**
     * The route shape vehicles drive along, as "latitude,longitude" pairs.
     *
     * <p>The default is the seeded M42 shape from the backend's V4 migration. It has to match the
     * seeded geometry, because the backend projects telemetry onto that geometry: a mismatch would
     * show up as route deviation that no scenario asked for.
     */
    private List<String> routePoints = List.of(
            "40.7128,-74.0060",
            "40.7140,-74.0020",
            "40.7152,-73.9980",
            "40.7163,-73.9945",
            "40.7178,-73.9900"
    );

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

    public List<String> getRoutePoints() {
        return routePoints;
    }

    public void setRoutePoints(List<String> routePoints) {
        this.routePoints = routePoints;
    }

    public List<String> getVehicleIds() {
        return vehicleIds;
    }

    public void setVehicleIds(List<String> vehicleIds) {
        this.vehicleIds = vehicleIds;
    }
}
