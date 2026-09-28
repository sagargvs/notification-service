package com.stornuage.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Idempotency record — prevents double-processing of the same signal.
 *
 * Written at the START of signal processing; if insertion fails due to
 * PK conflict, we know we've already seen this signal and can safely drop it.
 */
@Entity
@Table(name = "processed_signals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedSignal {

    @Id
    @Column(name = "signal_id", length = 128)
    private String signalId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @PrePersist
    void prePersist() {
        if (receivedAt == null) receivedAt = Instant.now();
    }
}
