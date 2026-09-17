package com.metropulse.operations.consumer;

import com.metropulse.operations.projection.UnknownVehicleInEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.apache.kafka.common.TopicPartition;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure handling for the operational-state consumer.
 *
 * <p>Two kinds of failure need different treatment:
 *
 * <ul>
 *   <li>Transient (the database is briefly unavailable): worth retrying, because the same event will
 *       succeed shortly. Retries are bounded so a permanent outage does not spin forever.</li>
 *   <li>Permanent (the event is malformed, or names a vehicle that does not exist): retrying cannot
 *       help. These are not retried at all; they go straight to the dead-letter topic.</li>
 * </ul>
 *
 * <p>Either way the event ends up on {@code <topic>.DLT} rather than blocking its partition. A stuck
 * partition would stall every vehicle whose events hash to it, not just the vehicle in the bad event.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Three attempts, two seconds apart, before an event is dead-lettered. */
    private static final int RETRY_ATTEMPTS = 2;
    private static final long RETRY_INTERVAL_MS = 2_000L;

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> deadLetterTopicFor(record, exception));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        // Poison messages: no amount of retrying turns these into valid events.
        errorHandler.addNotRetryableExceptions(
                MalformedEventException.class,
                UnknownVehicleInEventException.class);

        return errorHandler;
    }

    private static TopicPartition deadLetterTopicFor(ConsumerRecord<?, ?> record, Exception exception) {
        log.warn("Routing event from {}-{} offset {} to the dead-letter topic.",
                record.topic(), record.partition(), record.offset(), exception);

        // Keep the partition number so a vehicle's failed events stay grouped in the DLT too.
        return new TopicPartition(record.topic() + ".DLT", record.partition());
    }
}
