package com.metropulse.telemetry.domain;

public class InvalidIngestKeyException extends RuntimeException {

    public InvalidIngestKeyException() {
        super("Telemetry ingest key is invalid.");
    }
}
