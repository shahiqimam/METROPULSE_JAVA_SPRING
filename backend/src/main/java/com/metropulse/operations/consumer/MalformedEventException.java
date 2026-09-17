package com.metropulse.operations.consumer;

/**
 * Thrown when an event envelope cannot be read.
 *
 * <p>Retrying cannot fix a malformed event, so the consumer sends it straight to the dead-letter
 * topic instead of blocking the partition behind it.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
