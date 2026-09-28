package com.stornuage.notification.service;

import com.stornuage.notification.config.NotificationProperties;
import com.stornuage.notification.dto.AlertSignal;
import com.stornuage.notification.exception.InvalidSignalException;
import com.stornuage.notification.exception.StaleSignalException;
import com.stornuage.notification.service.AlertStateMachine.Decision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.Instant;

/**
 * Top-level processor for incoming signals. Called by the SQS consumer.
 *
 * Responsibilities:
 *   1. Validate signal payload (freshness, required fields)
 *   2. Delegate idempotency check to IdempotencyGate (separate bean, real @Transactional)
 *   3. Delegate state-machine decision to AlertStateMachine
 *   4. Based on decision, trigger notification delivery
 *
 * Transaction boundaries:
 *   IdempotencyGate.markIfUnseen runs in REQUIRES_NEW — marker persists
 *     independently so a crash mid-processing doesn't cause duplicates.
 *   AlertStateMachine.handle runs in its own @Transactional.
 *   Delivery runs in REQUIRES_NEW so FCM failure doesn't roll back state.
 *
 * This class itself is NOT @Transactional — it coordinates between
 * transactional collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalProcessor {

    private final IdempotencyGate idempotencyGate;
    private final AlertStateMachine stateMachine;
    private final CoalescedDeliveryService coalescedDeliveryService;
    private final NotificationProperties properties;

    /**
     * Entry point from SQS consumer.
     *
     * {@link InvalidSignalException} and {@link StaleSignalException} are
     * thrown for payloads that can never succeed — the consumer catches
     * them and acknowledges (no retry).
     *
     * Any other RuntimeException causes SQS redelivery (up to maxReceiveCount,
     * then DLQ).
     */
    public void process(AlertSignal signal) {
        validate(signal);

        if (!idempotencyGate.markIfUnseen(signal.getSignalId())) {
            log.info("Signal {} already processed — skipping", signal.getSignalId());
            return;
        }

        var decision = stateMachine.handle(signal);
        log.info("Signal {} → action={} reason={}",
                signal.getSignalId(), decision.action(), decision.reason());

        if (shouldDeliver(decision)) {
            var sent = coalescedDeliveryService.deliver(decision.alert());
            log.info("Alert {} delivered to {} devices", decision.alert().getAlertId(), sent);
        }
    }

    // =========================================================
    // Validation
    // =========================================================

    private void validate(AlertSignal signal) {
        if (signal.getSignalId() == null || signal.getSignalId().isBlank()) {
            throw new InvalidSignalException("Missing signalId");
        }
        if (signal.getContainerId() == null || signal.getContainerId().isBlank()) {
            throw new InvalidSignalException("Missing containerId");
        }
        if (signal.getUserId() == null || signal.getUserId().isBlank()) {
            throw new InvalidSignalException("Missing userId");
        }
        if (signal.getSignalType() == null) {
            throw new InvalidSignalException("Missing signalType");
        }
        if (signal.getDetectedAt() == null) {
            throw new InvalidSignalException("Missing detectedAt");
        }

        // Freshness: reject stale signals (edge case 12.3 from architecture spec)
        var age = Duration.between(signal.getDetectedAt(), Instant.now());
        var maxAge = Duration.ofMinutes(properties.getSignal().getMaxAgeMinutes());
        if (age.compareTo(maxAge) > 0) {
            throw new StaleSignalException(
                    "Signal " + signal.getSignalId() + " is " + age.toMinutes()
                            + "m old, rejecting");
        }

        // NOTE: Ownership validation (userId matches containers.user_id) should
        // happen here but requires a lookup to the containers table owned by
        // the Integration/Nutrition services. TODO: add ContainerOwnershipClient
        // that queries those services or reads from a replicated table.
    }

    private boolean shouldDeliver(Decision decision) {
        return decision.alert() != null && switch (decision.action()) {
            case FIRE_NEW, FIRE_UPGRADE, FIRE_REFIRE -> true;
            case SUPPRESS, RESOLVE, IGNORE -> false;
        };
    }
}
