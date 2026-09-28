package com.stornuage.notification.dto;

import com.stornuage.notification.domain.Platform;
import com.stornuage.notification.domain.UserDevice;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
        UUID deviceId,
        Platform platform,
        String appVersion,
        Instant createdAt,
        Instant lastSeenAt
) {
    public static DeviceResponse from(UserDevice d) {
        return new DeviceResponse(
                d.getDeviceId(),
                d.getPlatform(),
                d.getAppVersion(),
                d.getCreatedAt(),
                d.getLastSeenAt()
        );
    }
}
