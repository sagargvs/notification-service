package com.stornuage.notification.exception;

/** Signal arrived with a timestamp older than the configured threshold. */
public class StaleSignalException extends RuntimeException {
    public StaleSignalException(String message) { super(message); }
}
