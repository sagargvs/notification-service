package com.stornuage.notification.service;

import com.stornuage.notification.domain.AlertState;
import com.stornuage.notification.domain.AlertStateAudit;
import com.stornuage.notification.repository.AlertRepository;
import com.stornuage.notification.repository.AlertStateAuditRepository;
import com.stornuage.notification.repository.ProcessedSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Background maintenance jobs.
 *
 * Neither job drives notification flow — that's entirely event-driven from
 * the SQS consumer. These jobs exist to:
 *   1. Move acknowledged-but-still-low alerts back to ACTIVE so the next
 *      incoming signal re-fires (edge case 13.3).
 *   2. Purge idempotency records older than the SQS redrive window.
 *
 * Both run on Spring's task scheduler. In a multi-pod deployment, you'd
 * want a distributed lock (ShedLock, e.g.) to ensure single-pod execution.
 * For now, the jobs are idempotent — running twice is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceJobs {

    private static final Duration ACK_EXPIRY_WINDOW = Duration.ofHours(24);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final AlertRepository alertRepository;
    private final AlertStateAuditRepository auditRepository;
    private final ProcessedSignalRepository processedSignalRepository;

    /**
     * Every 30 minutes: re-activate alerts whose acknowledgement has expired.
     * This means the next incoming signal for that alert will re-fire rather
     * than be suppressed by cooldown.
     */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT1M")
    @Transactional
    public void expireAcknowledgements() {
        var cutoff = Instant.now().minus(ACK_EXPIRY_WINDOW);
        var expired = alertRepository.findAcknowledgedBefore(cutoff);
        if (expired.isEmpty()) return;

        for (var alert : expired) {
            alert.setState(AlertState.ACTIVE);
            alertRepository.save(alert);

            auditRepository.save(AlertStateAudit.builder()
                    .alertId(alert.getAlertId())
                    .fromState(AlertState.ACKNOWLEDGED.name())
                    .toState(AlertState.ACTIVE.name())
                    .reason("Acknowledgement window expired")
                    .build());
        }
        log.info("Expired acknowledgements on {} alerts", expired.size());
    }

    /**
     * Nightly: remove idempotency rows older than the redrive window.
     * Keeps the processed_signals table bounded — a signal whose dedup row
     * was pruned cannot be redelivered anyway (SQS retention is at most
     * 14 days, practically much shorter).
     */
    @Scheduled(cron = "0 17 2 * * *", zone = "UTC")  // 02:17 UTC every day
    @Transactional
    public void purgeIdempotencyRecords() {
        var cutoff = Instant.now().minus(IDEMPOTENCY_RETENTION);
        var deleted = processedSignalRepository.deleteOlderThan(cutoff);
        log.info("Purged {} idempotency records older than {}", deleted, cutoff);
    }
}
