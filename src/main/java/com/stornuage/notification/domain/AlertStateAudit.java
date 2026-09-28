package com.stornuage.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_state_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertStateAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Column(name = "from_state", length = 16)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 16)
    private String toState;

    @Column(name = "from_severity", length = 16)
    private String fromSeverity;

    @Column(name = "to_severity", length = 16)
    private String toSeverity;

    @Column(name = "trigger_signal", length = 128)
    private String triggerSignal;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) occurredAt = Instant.now();
        if (actor == null) actor = "SYSTEM";
    }
}
