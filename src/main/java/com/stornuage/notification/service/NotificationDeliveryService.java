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

/**
 * Delivers a notification to all active devices of the alert's owner.
 *
 * Writes a delivery log row per device attempt.
 * Revokes tokens that FCM reports as UNREGISTERED or INVALID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private final UserDeviceRepository userDeviceRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final FcmClient fcmClient;

    /**
     * Deliver a notification for a given alert.
     *
     * @return number of devices successfully notified
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deliver(Alert alert, NotificationContent content) {
        var devices = userDeviceRepository.findActiveByCustomerId(alert.getUserId());

        if (devices.isEmpty()) {
            log.info("No active devices for user {} — alert {} created but not delivered",
                    alert.getUserId(), alert.getAlertId());
            return 0;
        }

        int sent = 0;
        for (var device : devices) {
            if (deliverToDevice(alert, device, content)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean deliverToDevice(Alert alert, UserDevice device, NotificationContent content) {
        var result = fcmClient.send(device, alert, content);
        persistOutcome(alert, device, content, result);

        // Token revocation for invalid tokens
        if (result.outcome() == FcmResult.Outcome.TOKEN_UNREGISTERED
                || result.outcome() == FcmResult.Outcome.TOKEN_INVALID) {
            revokeDevice(device, result.outcome().name());
        }

        return result.outcome() == FcmResult.Outcome.SENT;
    }

    private void persistOutcome(Alert alert, UserDevice device,
                                NotificationContent content, FcmResult result) {
        var entry = NotificationDelivery.builder()
                .alertId(alert.getAlertId())
                .userId(alert.getUserId())
                .deviceId(device.getDeviceId())
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
        log.info("Revoked device {} for user {}: {}",
                device.getDeviceId(), device.getUserId(), reason);
    }
}
