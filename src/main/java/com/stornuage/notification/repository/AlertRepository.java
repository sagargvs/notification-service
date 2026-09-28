package com.stornuage.notification.repository;

import com.stornuage.notification.domain.Alert;
import com.stornuage.notification.domain.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alert repository.
 *
 * Concurrency strategy:
 *   - FIFO SQS with MessageGroupId = containerId serializes all signals
 *     for a given container through one consumer at a time.
 *   - @Version on Alert entity provides optimistic locking as a backstop.
 *   - No pessimistic locks — they add deadlock risk without benefit here.
 */
@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /**
     * Find the currently-open alert of a given type for a container.
     * "Open" = ACTIVE or ACKNOWLEDGED. RESOLVED alerts are history.
     */
    @Query("""
        SELECT a FROM Alert a
        WHERE a.containerId = :containerId
          AND a.alertType = :alertType
          AND a.state IN (com.stornuage.notification.domain.AlertState.ACTIVE,
                          com.stornuage.notification.domain.AlertState.ACKNOWLEDGED)
        """)
    Optional<Alert> findOpenByContainerAndType(
            @Param("containerId") String containerId,
            @Param("alertType") AlertType alertType);

    /**
     * Find all open alerts for a container across all types.
     * Used when FILL_CRITICAL arrives and we want to upgrade a DEPLETION_WARNING.
     * Also used on REFILL_DETECTED to resolve all stock-related alerts.
     */
    @Query("""
        SELECT a FROM Alert a
        WHERE a.containerId = :containerId
          AND a.state IN (com.stornuage.notification.domain.AlertState.ACTIVE,
                          com.stornuage.notification.domain.AlertState.ACKNOWLEDGED)
        """)
    List<Alert> findAllOpenByContainer(@Param("containerId") String containerId);

    /** History view — most recent alerts for a user. */
    List<Alert> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Periodic job: re-activate acknowledged alerts after cooldown. */
    @Query("""
        SELECT a FROM Alert a
        WHERE a.state = com.stornuage.notification.domain.AlertState.ACKNOWLEDGED
          AND a.acknowledgedAt < :cutoff
        """)
    List<Alert> findAcknowledgedBefore(@Param("cutoff") Instant cutoff);
}
