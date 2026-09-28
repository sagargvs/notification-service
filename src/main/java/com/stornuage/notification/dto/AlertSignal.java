package com.stornuage.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stornuage.notification.domain.SignalType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.util.Map;

/**
 * Signal payload published by producer services (Nutrition Service, Depletion Predictor).
 *
 * Contract:
 *  - signalId is a producer-generated UUID; used for idempotency
 *  - detectedAt is the moment the producer determined the threshold was crossed
 *  - itemName / itemDisplayName are frozen at detection time (important for race protection)
 *  - metric is type-specific payload (fill%, days remaining, battery level)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlertSignal {

    @NotBlank
    private String signalId;

    @NotNull
    private SignalType signalType;

    @NotBlank
    private String containerId;

    @NotBlank
    private String userId;

    /** snake_case item key; null for BATTERY_LOW when container is empty */
    private String itemName;

    /** human-friendly; null for BATTERY_LOW when container is empty */
    private String itemDisplayName;

    /**
     * Signal-type-specific metric:
     *   FILL_CRITICAL      → { "fillPercentage": 8.2 }
     *   DEPLETION_WARNING  → { "daysRemaining": 1.6, "currentWeightGrams": 180 }
     *   BATTERY_LOW        → { "batteryPercentage": 12 }
     *   REFILL_DETECTED    → { "previousFillPercentage": 8, "newFillPercentage": 85 }
     */
    private Map<String, Object> metric;

    @NotNull
    private Instant detectedAt;

    /** optional context for debugging */
    private String producerService;
}
