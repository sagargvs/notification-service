package com.stornuage.notification.client;

import com.google.firebase.messaging.*;
import com.stornuage.notification.config.NotificationProperties;
import com.stornuage.notification.domain.Alert;
import com.stornuage.notification.domain.Platform;
import com.stornuage.notification.domain.Severity;
import com.stornuage.notification.domain.UserDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcmClient {

    private final FirebaseMessaging firebaseMessaging;
    private final NotificationProperties properties;

    public record FcmResult(
            Outcome outcome,
            String messageId,
            String errorCode,
            String errorMessage) {

        public enum Outcome {
            SENT,
            TOKEN_UNREGISTERED,
            TOKEN_INVALID,
            QUOTA_EXCEEDED,
            INTERNAL_ERROR,
            UNKNOWN_ERROR
        }
    }

    /** deepLinkPath is appended to "stornuage://" in the FCM data payload. */
    public record NotificationContent(String title, String body, String deepLinkPath) {}

    /** Backward-compat alias — used by NotificationDeliveryService for non-coalesced sends. */
    public FcmResult send(UserDevice device, Alert alert, NotificationContent content) {
        return sendNew(device, alert, content, null);
    }

    /**
     * Fresh coalesced notification. Sets apns thread-id and Android tag for OS grouping.
     * Does NOT set apns-collapse-id — this delivers as a new notification with a buzz.
     */
    public FcmResult sendNew(UserDevice device, Alert alert, NotificationContent content, String batchKey) {
        var message = buildMessage(device, alert, content, batchKey, false);
        return sendWithRetry(message, "sendNew", alert, device);
    }

    /**
     * Updates an existing coalesced notification already on the device.
     * Sets apns-collapse-id = batchKey so iOS replaces the previous notification silently.
     * Android tag naturally replaces via the same tag mechanism as sendNew.
     */
    public FcmResult sendEdit(UserDevice device, Alert alert, NotificationContent content, String batchKey) {
        var message = buildMessage(device, alert, content, batchKey, true);
        return sendWithRetry(message, "sendEdit", alert, device);
    }

    private FcmResult sendWithRetry(Message message, String op, Alert alert, UserDevice device) {
        int attempts = 0;
        FcmResult lastResult = null;
        while (attempts < properties.getFcm().getMaxRetries()) {
            attempts++;
            try {
                var messageId = firebaseMessaging.send(message);
                log.debug("FCM {} ok: alert={} device={} msgId={}",
                        op, alert.getAlertId(), device.getDeviceId(), messageId);
                return new FcmResult(FcmResult.Outcome.SENT, messageId, null, null);
            } catch (FirebaseMessagingException e) {
                lastResult = interpretException(e);
                log.warn("FCM {} failed: alert={} device={} outcome={} attempt={}/{}",
                        op, alert.getAlertId(), device.getDeviceId(),
                        lastResult.outcome(), attempts, properties.getFcm().getMaxRetries());
                if (!isRetryable(lastResult.outcome())) return lastResult;
                sleepBackoff(attempts);
            }
        }
        return lastResult;
    }

    private Message buildMessage(UserDevice device, Alert alert, NotificationContent content,
                                  String batchKey, boolean withCollapseId) {
        var dataPayload = new HashMap<String, String>();
        dataPayload.put("alertId", alert.getAlertId().toString());
        dataPayload.put("containerId", alert.getContainerId());
        dataPayload.put("alertType", alert.getAlertType().name());
        dataPayload.put("severity", alert.getSeverity().name());
        // Deep link constructed server-side, never from user input — security
        dataPayload.put("deepLink", "stornuage://" + content.deepLinkPath());

        var builder = Message.builder()
                .setToken(device.getFcmToken())
                .setNotification(Notification.builder()
                        .setTitle(content.title())
                        .setBody(content.body())
                        .build())
                .putAllData(dataPayload);

        if (device.getPlatform() == Platform.IOS) {
            builder.setApnsConfig(buildApnsConfig(alert, batchKey, withCollapseId));
        } else {
            builder.setAndroidConfig(buildAndroidConfig(alert, batchKey));
        }
        return builder.build();
    }

    private ApnsConfig buildApnsConfig(Alert alert, String batchKey, boolean withCollapseId) {
        var priority = alert.getSeverity() == Severity.CRITICAL ? "10" : "5";

        var apnsBuilder = ApnsConfig.builder()
                .putHeader("apns-priority", priority);

        if (withCollapseId && batchKey != null) {
            apnsBuilder.putHeader("apns-collapse-id", batchKey);
        }

        var apsBuilder = Aps.builder()
                .setSound("default")
                .setBadge(1)
                .setContentAvailable(true);

        if (batchKey != null) {
            apsBuilder.setThreadId(batchKey);
        }

        return apnsBuilder.setAps(apsBuilder.build()).build();
    }

    private AndroidConfig buildAndroidConfig(Alert alert, String batchKey) {
        var priority = alert.getSeverity() == Severity.CRITICAL
                ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL;

        var notifBuilder = AndroidNotification.builder().setChannelId("low_stock_alerts");
        if (batchKey != null) {
            notifBuilder.setTag(batchKey);
        }

        var androidBuilder = AndroidConfig.builder()
                .setPriority(priority)
                .setNotification(notifBuilder.build());

        // collapseKey is the FCM-level Android grouping signal (closest SDK equivalent to
        // notification.group — OS-level grouping must be configured in the mobile app)
        if (batchKey != null) {
            androidBuilder.setCollapseKey(batchKey);
        }

        return androidBuilder.build();
    }

    private FcmResult interpretException(FirebaseMessagingException e) {
        var errorCode = e.getMessagingErrorCode();
        if (errorCode == null) {
            return new FcmResult(FcmResult.Outcome.UNKNOWN_ERROR, null,
                    e.getErrorCode() != null ? e.getErrorCode().name() : "UNKNOWN",
                    e.getMessage());
        }

        return switch (errorCode) {
            case UNREGISTERED -> new FcmResult(FcmResult.Outcome.TOKEN_UNREGISTERED,
                    null, errorCode.name(), e.getMessage());
            case INVALID_ARGUMENT, SENDER_ID_MISMATCH, THIRD_PARTY_AUTH_ERROR ->
                    new FcmResult(FcmResult.Outcome.TOKEN_INVALID,
                            null, errorCode.name(), e.getMessage());
            case QUOTA_EXCEEDED -> new FcmResult(FcmResult.Outcome.QUOTA_EXCEEDED,
                    null, errorCode.name(), e.getMessage());
            case INTERNAL, UNAVAILABLE -> new FcmResult(FcmResult.Outcome.INTERNAL_ERROR,
                    null, errorCode.name(), e.getMessage());
            default -> new FcmResult(FcmResult.Outcome.UNKNOWN_ERROR,
                    null, errorCode.name(), e.getMessage());
        };
    }

    private boolean isRetryable(FcmResult.Outcome outcome) {
        return outcome == FcmResult.Outcome.QUOTA_EXCEEDED
                || outcome == FcmResult.Outcome.INTERNAL_ERROR;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep((long) properties.getFcm().getRetryBackoffMs() * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
