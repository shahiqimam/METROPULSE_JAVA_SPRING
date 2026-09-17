package com.metropulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MetroPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetroPulseApplication.class, args);
    }
}
