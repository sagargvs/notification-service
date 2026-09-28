package com.stornuage.notification.service;

import com.stornuage.notification.config.NotificationProperties;
import com.stornuage.notification.domain.Alert;
import com.stornuage.notification.domain.AlertType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;

/**
 * In-memory per-user-per-stream batch manager.
 *
 * Batches are keyed by "{userId}:{streamKey}". When an alert is submitted:
 *  - If an active batch exists for the key: add the alert, reset the close timer,
 *    return UPDATE_EXISTING so the caller issues a silent edit-send.
 *  - If no active batch: create one, schedule the close timer, return FIRE_NEW_BATCH
 *    so the caller issues a fresh notification.
 *
 * The close timer only marks the batch closed and removes it from the map.
 * Delivery is driven by the caller (CoalescedDeliveryService) on every submit —
 * not deferred to the timer. This means notifications are immediate; coalescing
 * works by replacing the already-delivered notification via collapse semantics.
 *
 * Pod-local: batches live in memory only. On pod restart, any open batch is lost.
 * The worst outcome is one additional notification per user per stream; alert state
 * is persisted in the database and is not affected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoalescingService implements DisposableBean {

    public enum Stream {
        LOW_STOCK("low-stock"),
        BATTERY("battery");

        private final String key;

        Stream(String key) { this.key = key; }

        public String key() { return key; }

        public static Stream of(AlertType type) {
            return switch (type) {
                case FILL_CRITICAL, DEPLETION_WARNING -> LOW_STOCK;
                case BATTERY_LOW -> BATTERY;
            };
        }
    }

    public enum Decision { FIRE_NEW_BATCH, UPDATE_EXISTING }

    public record SubmitResult(Decision decision, Batch batch) {}

    public static class Batch {
        private final String key;
        private final Stream stream;
        private final CopyOnWriteArrayList<Alert> alerts = new CopyOnWriteArrayList<>();
        volatile String fcmMessageId;
        volatile boolean closed = false;
        volatile ScheduledFuture<?> closeTimer;

        Batch(String key, Stream stream) {
            this.key = key;
            this.stream = stream;
        }

        public void addAlert(Alert alert) {
            boolean duplicate = alerts.stream()
                    .anyMatch(a -> a.getAlertId().equals(alert.getAlertId()));
            if (!duplicate) {
                alerts.add(alert);
            }
        }

        public String getKey() { return key; }
        public Stream getStream() { return stream; }
        public String getFcmMessageId() { return fcmMessageId; }
        public List<Alert> getAlerts() { return List.copyOf(alerts); }
        public boolean isActive() { return !closed; }
    }

    private final ConcurrentHashMap<String, Batch> batches = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        var t = new Thread(r, "coalesce-timer");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Coalesce timer thread died unexpectedly", ex));
        return t;
    });

    private final NotificationProperties props;
    private volatile boolean destroyed = false;

    public SubmitResult submit(Alert alert) {
        if (destroyed) {
            // Pod is shutting down — throw so the SQS consumer does not ack and redelivers
            throw new IllegalStateException("CoalescingService is shutting down, signal will be redelivered");
        }

        var stream = Stream.of(alert.getAlertType());
        var key = alert.getUserId() + ":" + stream.key();
        var windowSeconds = props.getCoalescing().getWindowSeconds();

        var result = new SubmitResult[1];

        // compute() is atomic per key — safe check-then-act without additional locking
        batches.compute(key, (k, existing) -> {
            if (existing != null && existing.isActive()) {
                existing.addAlert(alert);
                if (existing.closeTimer != null) existing.closeTimer.cancel(false);
                existing.closeTimer = scheduler.schedule(() -> closeBatch(k), windowSeconds, TimeUnit.SECONDS);
                result[0] = new SubmitResult(Decision.UPDATE_EXISTING, existing);
                return existing;
            } else {
                var batch = new Batch(k, stream);
                batch.addAlert(alert);
                batch.closeTimer = scheduler.schedule(() -> closeBatch(k), windowSeconds, TimeUnit.SECONDS);
                result[0] = new SubmitResult(Decision.FIRE_NEW_BATCH, batch);
                return batch;
            }
        });

        log.debug("Coalescing submit: key={} decision={} batchSize={}",
                key, result[0].decision(), result[0].batch().alerts.size());

        return result[0];
    }

    public void recordFirstSend(Batch batch, String fcmMessageId) {
        batch.fcmMessageId = fcmMessageId;
    }

    /** Exposed for actuator metrics. */
    public int activeBatchCount() {
        return (int) batches.values().stream().filter(Batch::isActive).count();
    }

    private void closeBatch(String key) {
        batches.computeIfPresent(key, (k, batch) -> {
            batch.closed = true;
            log.debug("Coalescing batch closed: key={} alertCount={}", k, batch.alerts.size());
            return null;
        });
    }

    @Override
    public void destroy() {
        destroyed = true;
        scheduler.shutdownNow();
        batches.clear();
    }
}
