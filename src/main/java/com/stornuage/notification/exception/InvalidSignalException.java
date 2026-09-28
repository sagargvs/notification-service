package com.stornuage.notification.exception;

/**
 * Signal payload failed validation — user mismatch, unknown container, etc.
 * Thrown to DLQ rather than retried; the message is malformed or a security concern.
 */
public class InvalidSignalException extends RuntimeException {
    public InvalidSignalException(String message) { super(message); }
}
