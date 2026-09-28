package com.stornuage.notification.service;

import com.stornuage.notification.config.NotificationProperties;
import com.stornuage.notification.domain.*;
import com.stornuage.notification.dto.AlertSignal;
import com.stornuage.notification.repository.AlertRepository;
import com.stornuage.notification.repository.AlertStateAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * State machine for alerts.
 *
 * Decides, given a signal, whether to:
 *   - CREATE a new alert and fire notification
 *   - UPGRADE severity of existing alert (e.g. WARNING → CRITICAL)
 *   - RE-FIRE existing alert (cooldown expired)
 *   - SUPPRESS (within cooldown, no severity change, escalation max not hit)
 *   - RESOLVE all open alerts (REFILL_DETECTED)
 *   - IGNORE (new DEPLETION_WARNING while CRITICAL already exists)
 *
 * The decision is returned as a {@link Decision} record; the caller
 * (SignalProcessor) performs the side effect of firing the notification.
 * This separation keeps the state machine pure and testable.
 *
 * Concurrency: all mutations run inside @Transactional. @Version on Alert
 * causes OptimisticLockException on concurrent writes — caller should
 * retry on the next delivery. FIFO SQS serialization by containerId
 * makes conflicts rare in practice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertStateMachine {

    private final AlertRepository alertRepository;
    private final AlertStateAuditRepository auditRepository;
    private final NotificationProperties properties;

    /**
     * Decision returned to the caller describing what side effect to perform.
     * The alert field is the post-state persisted row; may be null for IGNORE.
     */
    public record Decision(
            Action action,
            Alert alert,
            String reason) {

        public enum Action {
            FIRE_NEW,       // New alert created, send notification
            FIRE_UPGRADE,   // Severity upgraded, send upgrade notification
            FIRE_REFIRE,    // Cooldown expired, re-send
            SUPPRESS,       // Within cooldown or escalation maxed, no send
            RESOLVE,        // REFILL_DETECTED — all alerts cleared, no send
            IGNORE          // Lower-priority signal arrived under higher-priority alert
        }
    }

    // =========================================================
    // Entry point: handle a validated signal
    // =========================================================

    @Transactional
    public Decision handle(AlertSignal signal) {
        log.debug("Handling signal: type={} container={} user={}",
                signal.getSignalType(), signal.getContainerId(), signal.getUserId());

        return switch (signal.getSignalType()) {
            case REFILL_DETECTED -> handleRefill(signal);
            case FILL_CRITICAL,
                 DEPLETION_WARNING,
                 BATTERY_LOW -> handleAlertSignal(signal);
        };
    }

    // =========================================================
    // REFILL: resolve all open stock alerts for this container
    // =========================================================

    private Decision handleRefill(AlertSignal signal) {
        var openAlerts = alertRepository.findAllOpenByContainer(signal.getContainerId());

        // Only stock alerts get resolved by refill. Battery alerts persist.
        var resolvedCount = 0;
        for (var alert : openAlerts) {
            if (alert.getAlertType() == AlertType.BATTERY_LOW) continue;

            var previousState = alert.getState();
            alert.setState(AlertState.RESOLVED);
            alert.setResolvedAt(Instant.now());
            alert.setResolvedReason("REFILL_DETECTED");
            alertRepository.save(alert);

            audit(alert, previousState.name(), AlertState.RESOLVED.name(),
                    signal, "Refill detected");
            resolvedCount++;
        }

        log.info("Refill signal for container {}: resolved {} alerts",
                signal.getContainerId(), resolvedCount);

        // Return the first resolved alert for logging; null if none.
        var firstResolved = openAlerts.stream()
                .filter(a -> a.getAlertType() != AlertType.BATTERY_LOW)
                .findFirst()
                .orElse(null);

        return new Decision(Decision.Action.RESOLVE, firstResolved,
                "Resolved " + resolvedCount + " alerts on refill");
    }

    // =========================================================
    // Main alert handling — FILL_CRITICAL, DEPLETION_WARNING, BATTERY_LOW
    // =========================================================

    private Decision handleAlertSignal(AlertSignal signal) {
        var alertType = toAlertType(signal.getSignalType());
        var severity = defaultSeverityFor(signal.getSignalType());

        // Cross-alert-type logic: if a FILL_CRITICAL is already active,
        // DEPLETION_WARNING for the same container is subsumed.
        if (signal.getSignalType() == SignalType.DEPLETION_WARNING) {
            var criticalOpt = alertRepository.findOpenByContainerAndType(
                    signal.getContainerId(), AlertType.FILL_CRITICAL);
            if (criticalOpt.isPresent()) {
                log.debug("DEPLETION_WARNING subsumed by existing FILL_CRITICAL for container {}",
                        signal.getContainerId());
                return new Decision(Decision.Action.IGNORE, criticalOpt.get(),
                        "Subsumed by active FILL_CRITICAL");
            }
        }

        // Upgrade path: FILL_CRITICAL arriving while DEPLETION_WARNING active
        if (signal.getSignalType() == SignalType.FILL_CRITICAL) {
            var warningOpt = alertRepository.findOpenByContainerAndType(
                    signal.getContainerId(), AlertType.DEPLETION_WARNING);
            if (warningOpt.isPresent()) {
                return upgradeAlert(warningOpt.get(), signal);
            }
        }

        // Normal path: check for existing alert of same type
        var existingOpt = alertRepository.findOpenByContainerAndType(
                signal.getContainerId(), alertType);

        if (existingOpt.isEmpty()) {
            return createNewAlert(signal, alertType, severity);
        }

        return reconsiderExisting(existingOpt.get(), signal);
    }

    // =========================================================
    // Creating a brand new alert
    // =========================================================

    private Decision createNewAlert(AlertSignal signal, AlertType type, Severity severity) {
        var now = Instant.now();
        var alert = Alert.builder()
                .userId(signal.getUserId())
                .containerId(signal.getContainerId())
                .alertType(type)
                .severity(severity)
                .state(AlertState.ACTIVE)
                .itemName(signal.getItemName())
                .itemDisplayName(signal.getItemDisplayName())
                .metricPayload(signal.getMetric())
                .escalationLevel(1)
                .firstFiredAt(now)
                .lastFiredAt(now)
                .lastNotificationSentAt(now)
                .build();

        alert = alertRepository.save(alert);
        audit(alert, null, AlertState.ACTIVE.name(), signal, "New alert created");

        log.info("Created new {} alert for container {}, severity {}",
                type, signal.getContainerId(), severity);

        return new Decision(Decision.Action.FIRE_NEW, alert, "New alert");
    }

    // =========================================================
    // Upgrading WARNING → CRITICAL in place
    // =========================================================

    private Decision upgradeAlert(Alert existing, AlertSignal signal) {
        var previousSeverity = existing.getSeverity();
        var previousType = existing.getAlertType();

        existing.setAlertType(AlertType.FILL_CRITICAL);
        existing.setSeverity(Severity.CRITICAL);
        existing.setState(AlertState.ACTIVE);    // in case it was ACKNOWLEDGED
        existing.setItemName(signal.getItemName());
        existing.setItemDisplayName(signal.getItemDisplayName());
        existing.setMetricPayload(signal.getMetric());
        existing.setEscalationLevel(existing.getEscalationLevel() + 1);
        existing.setLastFiredAt(Instant.now());
        existing.setLastNotificationSentAt(Instant.now());

        existing = alertRepository.save(existing);

        auditUpgrade(existing, previousType, previousSeverity, signal);

        log.info("Upgraded alert {} from {} to {} for container {}",
                existing.getAlertId(), previousSeverity, Severity.CRITICAL,
                signal.getContainerId());

        return new Decision(Decision.Action.FIRE_UPGRADE, existing, "Severity upgraded");
    }

    // =========================================================
    // Reconsidering an existing alert of same type
    // =========================================================

    private Decision reconsiderExisting(Alert existing, AlertSignal signal) {
        // Escalation max check — stop firing but keep alert ACTIVE
        if (existing.getEscalationLevel() >= properties.getEscalation().getMaxLevel()) {
            // Still update last_fired_at for observability, but don't notify
            existing.setLastFiredAt(Instant.now());
            existing.setMetricPayload(signal.getMetric());
            alertRepository.save(existing);

            log.info("Alert {} escalation maxed at level {}, suppressing",
                    existing.getAlertId(), existing.getEscalationLevel());
            return new Decision(Decision.Action.SUPPRESS, existing,
                    "Escalation level maxed at " + existing.getEscalationLevel());
        }

        // Cooldown check — do not re-notify within window unless state was ACKNOWLEDGED.
        // ACKNOWLEDGED alerts that come back still respect the ack-cooldown which is
        // checked by the AcknowledgementExpiry scheduled job before this point;
        // if we see an ACKNOWLEDGED alert here, it means ack has already expired.
        var sinceLastNotify = Duration.between(
                existing.getLastNotificationSentAt(), Instant.now());
        var cooldown = Duration.ofHours(properties.getCooldown().getSameSeverityHours());

        if (sinceLastNotify.compareTo(cooldown) < 0
                && existing.getState() == AlertState.ACTIVE) {
            existing.setLastFiredAt(Instant.now());
            existing.setMetricPayload(signal.getMetric());
            alertRepository.save(existing);

            log.debug("Alert {} within cooldown, suppressing", existing.getAlertId());
            return new Decision(Decision.Action.SUPPRESS, existing,
                    "Within cooldown window");
        }

        // Re-fire: cooldown expired or alert was ACKNOWLEDGED and re-triggered
        var previousState = existing.getState();
        existing.setState(AlertState.ACTIVE);
        existing.setEscalationLevel(existing.getEscalationLevel() + 1);
        existing.setMetricPayload(signal.getMetric());
        existing.setLastFiredAt(Instant.now());
        existing.setLastNotificationSentAt(Instant.now());
        alertRepository.save(existing);

        audit(existing, previousState.name(), AlertState.ACTIVE.name(),
                signal, "Cooldown expired, re-firing");

        log.info("Re-firing alert {} (escalation={}) for container {}",
                existing.getAlertId(), existing.getEscalationLevel(),
                signal.getContainerId());
        return new Decision(Decision.Action.FIRE_REFIRE, existing, "Re-fire after cooldown");
    }

    // =========================================================
    // External API: acknowledge an alert (called from REST controller
    // when user opens the container view in the app)
    // =========================================================

    @Transactional
    public Optional<Alert> acknowledge(java.util.UUID alertId, String actingUserId) {
        var opt = alertRepository.findById(alertId);
        if (opt.isEmpty()) return Optional.empty();

        var alert = opt.get();

        // Security: user can only ack their own alerts
        if (!alert.getUserId().equals(actingUserId)) {
            log.warn("User {} attempted to acknowledge alert {} owned by {}",
                    actingUserId, alertId, alert.getUserId());
            return Optional.empty();
        }

        if (alert.getState() == AlertState.RESOLVED
                || alert.getState() == AlertState.ACKNOWLEDGED) {
            return Optional.of(alert);  // idempotent
        }

        var previousState = alert.getState();
        alert.setState(AlertState.ACKNOWLEDGED);
        alert.setAcknowledgedAt(Instant.now());
        alertRepository.save(alert);

        var audit = AlertStateAudit.builder()
                .alertId(alert.getAlertId())
                .fromState(previousState.name())
                .toState(AlertState.ACKNOWLEDGED.name())
                .actor(actingUserId)
                .reason("User acknowledged via app")
                .build();
        auditRepository.save(audit);

        log.info("Alert {} acknowledged by user {}", alertId, actingUserId);
        return Optional.of(alert);
    }

    // =========================================================
    // External API: cross-service cleanup hook.
    // Called from a scheduled task when containers go dormant or items change.
    // =========================================================

    @Transactional
    public void resolveAlertsForContainer(String containerId, String reason) {
        var openAlerts = alertRepository.findAllOpenByContainer(containerId);
        for (var alert : openAlerts) {
            var previousState = alert.getState();
            alert.setState(AlertState.RESOLVED);
            alert.setResolvedAt(Instant.now());
            alert.setResolvedReason(reason);
            alertRepository.save(alert);
            audit(alert, previousState.name(), AlertState.RESOLVED.name(), null, reason);
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private AlertType toAlertType(SignalType signalType) {
        return switch (signalType) {
            case FILL_CRITICAL -> AlertType.FILL_CRITICAL;
            case DEPLETION_WARNING -> AlertType.DEPLETION_WARNING;
            case BATTERY_LOW -> AlertType.BATTERY_LOW;
            case REFILL_DETECTED -> throw new IllegalStateException(
                    "REFILL_DETECTED should not reach toAlertType()");
        };
    }

    private Severity defaultSeverityFor(SignalType signalType) {
        return switch (signalType) {
            case FILL_CRITICAL -> Severity.CRITICAL;
            case DEPLETION_WARNING, BATTERY_LOW -> Severity.WARNING;
            case REFILL_DETECTED -> Severity.WARNING;
        };
    }

    private void audit(Alert alert, String from, String to, AlertSignal signal, String reason) {
        var a = AlertStateAudit.builder()
                .alertId(alert.getAlertId())
                .fromState(from)
                .toState(to)
                .toSeverity(alert.getSeverity().name())
                .triggerSignal(signal != null ? signal.getSignalId() : null)
                .reason(reason)
                .build();
        auditRepository.save(a);
    }

    private void auditUpgrade(Alert alert, AlertType prevType, Severity prevSeverity,
                              AlertSignal signal) {
        var a = AlertStateAudit.builder()
                .alertId(alert.getAlertId())
                .fromState(AlertState.ACTIVE.name())
                .toState(AlertState.ACTIVE.name())
                .fromSeverity(prevSeverity.name())
                .toSeverity(alert.getSeverity().name())
                .triggerSignal(signal.getSignalId())
                .reason("Upgraded from " + prevType + " to " + alert.getAlertType())
                .build();
        auditRepository.save(a);
    }
}
