package com.stornuage.notification.domain;

/**
 * Signal types emitted by producers (Nutrition Service, Depletion Predictor).
 *
 * Maps to AlertType but with REFILL_DETECTED being a control signal
 * that resolves alerts rather than creating new ones.
 */
public enum SignalType {
    FILL_CRITICAL,
    DEPLETION_WARNING,
    BATTERY_LOW,
    REFILL_DETECTED
}
