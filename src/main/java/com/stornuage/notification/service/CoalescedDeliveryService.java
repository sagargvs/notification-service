package com.stornuage.notification.service;

import com.stornuage.notification.client.FcmClient;
import com.stornuage.notification.client.FcmClient.FcmResult;
import com.stornuage.notification.client.FcmClient.NotificationContent;
import com.stornuage.notification.domain.*;
import com.stornuage.notification.repository.NotificationDeliveryRepository;
import com.stornuage.notification.repository.UserDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoalescedDeliveryService {

    private final CoalescingService coalescingService;
    private final NotificationComposer composer;
    private final FcmClient fcmClient;
    private final UserDeviceRepository userDeviceRepository;
    private final NotificationDeliveryRepository deliveryRepository;

    /**
     * Submits the alert to the coalescing service, then delivers immediately.
     *
     * FIRE_NEW_BATCH  → sendNew() — fresh notification with buzz.
     * UPDATE_EXISTING → sendEdit() — silent replacement via collapse semantics.
     *
     * @return number of devices successfully delivered to
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deliver(Alert alert) {
        var devices = userDeviceRepository.findActiveByCustomerId(alert.getUserId());
        if (devices.isEmpty()) {
            log.info("No active devices for userId={} — alert {} not delivered",
                    alert.getUserId(), alert.getAlertId());
            return 0;
        }

        var submitResult = coalescingService.submit(alert);
        var batch = submitResult.batch();
        var batchKey = batch.getKey();
        var content = composer.composeBatch(batch.getAlerts());

        int sent = 0;
        for (var device : devices) {
            var result = submitResult.decision() == CoalescingService.Decision.FIRE_NEW_BATCH
                    ? fcmClient.sendNew(device, alert, content, batchKey)
                    : fcmClient.sendEdit(device, alert, content, batchKey);

            persistDelivery(alert, device, content, result, batchKey);

            if (result.outcome() == FcmResult.Outcome.SENT) {
                sent++;
                // Record the first successful messageId so future edits can reference it
                if (submitResult.decision() == CoalescingService.Decision.FIRE_NEW_BATCH
                        && batch.getFcmMessageId() == null) {
                    coalescingService.recordFirstSend(batch, result.messageId());
                }
            } else if (result.outcome() == FcmResult.Outcome.TOKEN_UNREGISTERED
                    || result.outcome() == FcmResult.Outcome.TOKEN_INVALID) {
                revokeDevice(device, result.outcome().name());
            } else if (submitResult.decision() == CoalescingService.Decision.UPDATE_EXISTING) {
                log.warn("Coalesce edit failed: device={} batchKey={} outcome={} error_code=COALESCE_EDIT_FAILED",
                        device.getDeviceId(), batchKey, result.outcome());
            }
        }

        log.info("Coalesced delivery: alert={} batchKey={} decision={} sent={}/{}",
                alert.getAlertId(), batchKey, submitResult.decision(), sent, devices.size());
        return sent;
    }

    private void persistDelivery(Alert alert, UserDevice device, NotificationContent content,
                                  FcmResult result, String collapseKey) {
        var entry = NotificationDelivery.builder()
                .alertId(alert.getAlertId())
                .userId(alert.getUserId())
                .deviceId(device.getDeviceId())
                .collapseKey(collapseKey)
                .notificationTitle(content.title())
                .notificationBody(content.body())
                .fcmMessageId(result.messageId())
                .status(mapStatus(result.outcome()))
                .errorCode(result.errorCode())
                .errorMessage(result.errorMessage())
                .sentAt(result.outcome() == FcmResult.Outcome.SENT ? Instant.now() : null)
                .failedAt(result.outcome() != FcmResult.Outcome.SENT ? Instant.now() : null)
                .build();
        deliveryRepository.save(entry);
    }

    private DeliveryStatus mapStatus(FcmResult.Outcome outcome) {
        return switch (outcome) {
            case SENT -> DeliveryStatus.SENT;
            case TOKEN_UNREGISTERED, TOKEN_INVALID -> DeliveryStatus.DROPPED;
            default -> DeliveryStatus.FAILED;
        };
    }

    private void revokeDevice(UserDevice device, String reason) {
        device.setRevokedAt(Instant.now());
        device.setRevokeReason(reason);
        userDeviceRepository.save(device);
        log.info("Revoked device {} for user {}: {}", device.getDeviceId(), device.getUserId(), reason);
    }
}
