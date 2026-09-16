package com.metropulse.simulator;

import com.metropulse.simulator.config.SimulatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class MetroPulseSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetroPulseSimulatorApplication.class, args);
    }
}
