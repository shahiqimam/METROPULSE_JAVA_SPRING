package com.metropulse.outbox.publisher;

import com.metropulse.common.config.MetroPulseProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /** Telemetry is partitioned by vehicle id, so events for one vehicle stay ordered. */
    private static final int PARTITIONS = 3;

    @Bean
    NewTopic telemetryOutboxTopic(MetroPulseProperties properties) {
        return TopicBuilder.name(properties.outbox().topic())
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    /**
     * Dead-letter topic for events the operational-state consumer cannot apply.
     *
     * <p>It carries the same partition count as the source topic because the consumer preserves the
     * original partition when dead-lettering, which keeps one vehicle's failed events together.
     */
    @Bean
    NewTopic telemetryDeadLetterTopic(MetroPulseProperties properties) {
        return TopicBuilder.name(properties.outbox().topic() + ".DLT")
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }
}
