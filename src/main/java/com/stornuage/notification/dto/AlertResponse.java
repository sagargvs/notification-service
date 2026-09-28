package com.stornuage.notification.dto;

import com.stornuage.notification.domain.Alert;
import com.stornuage.notification.domain.AlertState;
import com.stornuage.notification.domain.AlertType;
import com.stornuage.notification.domain.Severity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertResponse(
        UUID alertId,
        String containerId,
        AlertType alertType,
        Severity severity,
        AlertState state,
        String itemDisplayName,
        Map<String, Object> metricPayload,
        int escalationLevel,
        Instant firstFiredAt,
        Instant lastFiredAt,
        Instant acknowledgedAt,
        Instant resolvedAt
) {
    public static AlertResponse from(Alert a) {
        return new AlertResponse(
                a.getAlertId(),
                a.getContainerId(),
                a.getAlertType(),
                a.getSeverity(),
                a.getState(),
                a.getItemDisplayName(),
                a.getMetricPayload(),
                a.getEscalationLevel(),
                a.getFirstFiredAt(),
                a.getLastFiredAt(),
                a.getAcknowledgedAt(),
                a.getResolvedAt()
        );
    }
}
