package com.metropulse.common.api;

import com.metropulse.alert.domain.AlertAlreadyClosedException;
import com.metropulse.alert.domain.UnknownAlertException;
import com.metropulse.telemetry.domain.InvalidIngestKeyException;
import com.metropulse.telemetry.domain.UnknownVehicleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request validation failed.");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingRequestHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_HEADER", "Missing required header: " + ex.getHeaderName(), request);
    }

    @ExceptionHandler(InvalidIngestKeyException.class)
    public ResponseEntity<ApiError> handleInvalidIngestKey(InvalidIngestKeyException ex, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_INGEST_KEY", ex.getMessage(), request);
    }

    @ExceptionHandler(UnknownVehicleException.class)
    public ResponseEntity<ApiError> handleUnknownVehicle(UnknownVehicleException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "UNKNOWN_VEHICLE", ex.getMessage(), request);
    }

    @ExceptionHandler(UnknownAlertException.class)
    public ResponseEntity<ApiError> handleUnknownAlert(UnknownAlertException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "UNKNOWN_ALERT", ex.getMessage(), request);
    }

    @ExceptionHandler(AlertAlreadyClosedException.class)
    public ResponseEntity<ApiError> handleAlertAlreadyClosed(AlertAlreadyClosedException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "ALERT_ALREADY_CLOSED", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        log.error("Unexpected request failure. requestId={} method={} path={}", requestId, request.getMethod(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error.", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), code, message, Instant.now(), requestId));
    }
}

