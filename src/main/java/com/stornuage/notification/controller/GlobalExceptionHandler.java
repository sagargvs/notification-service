package com.stornuage.notification.controller;

import com.stornuage.notification.exception.InvalidSignalException;
import com.stornuage.notification.exception.RateLimitExceededException;
import com.stornuage.notification.exception.StaleSignalException;
import com.stornuage.notification.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Central exception → HTTP status mapping. Keeps controllers clean.
 *
 * Response body is a minimal JSON object with `error`, `timestamp`, and
 * optional `details` — no stack traces leak to clients.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException e) {
        return build(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        var detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", Map.of("fields", detail));
    }

    @ExceptionHandler(InvalidSignalException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSignal(InvalidSignalException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(StaleSignalException.class)
    public ResponseEntity<Map<String, Object>> handleStale(StaleSignalException e) {
        return build(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict: {}", e.getMessage());
        return build(HttpStatus.CONFLICT, "Concurrent modification; please retry");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error) {
        return build(status, error, null);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error,
                                                       Map<String, ?> details) {
        var body = new java.util.HashMap<String, Object>();
        body.put("error", error);
        body.put("status", status.value());
        body.put("timestamp", Instant.now().toString());
        if (details != null) body.putAll(details);
        return ResponseEntity.status(status).body(body);
    }
}
