package com.metropulse.common.api;

import java.time.Instant;

public record ApiError(
        int status,
        String code,
        String message,
        Instant timestamp,
        String requestId
) {
}
