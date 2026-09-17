package com.metropulse.outbox.publisher;

import java.util.UUID;

record OutboxEvent(
        UUID id,
        String aggregateId,
        String eventType,
        String payload
) {
}
