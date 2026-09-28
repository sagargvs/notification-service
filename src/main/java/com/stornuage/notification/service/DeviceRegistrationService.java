package com.stornuage.notification.service;

import com.stornuage.notification.domain.Platform;
import com.stornuage.notification.domain.UserDevice;
import com.stornuage.notification.repository.UserDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Device registration and revocation.
 *
 * Called from DeviceController. Never exposed over SQS or other entry points.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistrationService {

    private final UserDeviceRepository userDeviceRepository;

    /**
     * Register (or refresh) a device's FCM token for a user.
     * Upsert semantics: if the (user, token) pair already exists and is
     * active, bump last_seen_at and app metadata. Otherwise insert.
     */
    @Transactional
    public UserDevice register(String userId,
                               String customerId,
                               String fcmToken,
                               Platform platform,
                               String deviceModel,
                               String osVersion,
                               String appVersion) {
        var existingOpt = userDeviceRepository.findActiveByUserAndToken(userId, fcmToken);

        if (existingOpt.isPresent()) {
            var existing = existingOpt.get();
            existing.setLastSeenAt(Instant.now());
            existing.setDeviceModel(deviceModel);
            existing.setOsVersion(osVersion);
            existing.setAppVersion(appVersion);
            log.debug("Refreshed device {} for user {}", existing.getDeviceId(), userId);
            return userDeviceRepository.save(existing);
        }

        var device = UserDevice.builder()
                .userId(userId)
                .customerId(customerId)
                .fcmToken(fcmToken)
                .platform(platform)
                .deviceModel(deviceModel)
                .osVersion(osVersion)
                .appVersion(appVersion)
                .build();
        device = userDeviceRepository.save(device);
        log.info("Registered new device {} for user {} and customer {} on {}",
                device.getDeviceId(), userId, customerId, platform);
        return device;
    }

    /**
     * Revoke a device — e.g. user logged out on that device.
     * Only succeeds if the device belongs to the user asking (security).
     */
    @Transactional
    public Optional<UserDevice> revoke(UUID deviceId, String actingUserId, String reason) {
        var opt = userDeviceRepository.findById(deviceId);
        if (opt.isEmpty()) return Optional.empty();

        var device = opt.get();
        if (!device.getUserId().equals(actingUserId)) {
            log.warn("User {} attempted to revoke device {} owned by {}",
                    actingUserId, deviceId, device.getUserId());
            return Optional.empty();
        }

        if (device.getRevokedAt() != null) {
            return Optional.of(device);  // idempotent
        }

        device.setRevokedAt(Instant.now());
        device.setRevokeReason(reason != null ? reason : "USER_REQUESTED");
        return Optional.of(userDeviceRepository.save(device));
    }

    /**
     * Revoke all active tokens for a user — used on full logout.
     */
    @Transactional
    public int revokeAllForUser(String userId, String reason) {
        var devices = userDeviceRepository.findActiveByUserId(userId);
        var now = Instant.now();
        for (var d : devices) {
            d.setRevokedAt(now);
            d.setRevokeReason(reason != null ? reason : "USER_REQUESTED");
        }
        userDeviceRepository.saveAll(devices);
        return devices.size();
    }
}
