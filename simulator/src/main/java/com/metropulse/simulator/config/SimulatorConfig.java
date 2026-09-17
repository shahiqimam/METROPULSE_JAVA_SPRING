package com.metropulse.simulator.config;

import com.metropulse.simulator.route.GeoPoint;
import com.metropulse.simulator.route.RoutePath;
import com.metropulse.simulator.scenario.FleetSimulator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

@Configuration
public class SimulatorConfig {

    @Bean
    RoutePath routePath(SimulatorProperties properties) {
        List<GeoPoint> points = properties.getRoutePoints().stream()
                .map(SimulatorConfig::parsePoint)
                .toList();
        return new RoutePath(points);
    }

    @Bean
    FleetSimulator fleetSimulator(SimulatorProperties properties, RoutePath routePath) {
        return new FleetSimulator(
                routePath,
                properties.getVehicleIds(),
                properties.getScenario(),
                properties.getInterval(),
                properties.getSeed(),
                Long.toString(Instant.now().toEpochMilli()));
    }

    private static GeoPoint parsePoint(String value) {
        String[] parts = value.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Route points must be formatted as \"latitude,longitude\" but was: " + value);
        }
        return new GeoPoint(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
    }
}
