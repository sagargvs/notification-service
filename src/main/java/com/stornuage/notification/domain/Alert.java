package com.stornuage.notification.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The persistent state of an alert for a given container + alert type.
 *
 * Uniqueness is enforced at the DB level via a partial unique index:
 * only one row per (container_id, alert_type) can be in non-resolved state.
 *
 * We use @Version for optimistic locking so concurrent consumers
 * do not overwrite each other's updates.
 */
@Entity
@Table(name = "alert_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "container_id", nullable = false, length = 64)
    private String containerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 32)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private AlertState state;

    @Column(name = "item_name", length = 128)
    private String itemName;

    @Column(name = "item_display_name", length = 128)
    private String itemDisplayName;

    // Native Hibernate 6 JSONB handling — driver sends as `jsonb` not text
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metric_payload", columnDefinition = "jsonb")
    private Map<String, Object> metricPayload;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "first_fired_at", nullable = false)
    private Instant firstFiredAt;

    @Column(name = "last_fired_at", nullable = false)
    private Instant lastFiredAt;

    @Column(name = "last_notification_sent_at")
    private Instant lastNotificationSentAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_reason", length = 64)
    private String resolvedReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (firstFiredAt == null) firstFiredAt = now;
        if (lastFiredAt == null) lastFiredAt = now;
        if (escalationLevel == 0) escalationLevel = 1;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
