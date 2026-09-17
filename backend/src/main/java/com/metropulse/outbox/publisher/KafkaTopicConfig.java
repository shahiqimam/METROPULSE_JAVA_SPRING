package com.metropulse.outbox.publisher;

import com.metropulse.common.config.MetroPulseProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic telemetryOutboxTopic(MetroPulseProperties properties) {
        return TopicBuilder.name(properties.outbox().topic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
