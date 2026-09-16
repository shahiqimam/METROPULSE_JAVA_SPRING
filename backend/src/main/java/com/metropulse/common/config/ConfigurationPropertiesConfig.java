package com.metropulse.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MetroPulseProperties.class)
public class ConfigurationPropertiesConfig {
}
