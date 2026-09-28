package com.stornuage.notification.controller;

import com.stornuage.notification.auth.AuthenticatedUser;
import com.stornuage.notification.dto.DeviceRegistrationRequest;
import com.stornuage.notification.dto.DeviceResponse;
import com.stornuage.notification.exception.RateLimitExceededException;
import com.stornuage.notification.exception.UnauthorizedException;
import com.stornuage.notification.service.DeviceRegistrationService;
import com.stornuage.notification.service.UserRateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Device registration REST endpoints.
 *
 * All endpoints require a valid ThingsBoard JWT Bearer token.
 * The userId is ALWAYS taken from the JWT, never from the request body —
 * this is the primary control against token hijacking (security edge case 1).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private static final String RATE_SCOPE = "device-register";

    private final DeviceRegistrationService registrationService;
    private final UserRateLimiter rateLimiter;

    /**
     * Register or refresh a device's FCM token.
     * Called by the app on login, on FCM onNewToken callback, and on cold start.
     */
    @PostMapping
    public ResponseEntity<DeviceResponse> register(
            @Valid @RequestBody DeviceRegistrationRequest request,
            AuthenticatedUser user) {

        requireAuth(user);

        if (!rateLimiter.tryAcquire(RATE_SCOPE, user.userId())) {
            log.warn("Registration rate limit exceeded for user {}", user.userId());
            throw new RateLimitExceededException(
                    "Too many registration attempts; try again later");
        }

        var device = registrationService.register(
                user.userId(),
                user.customerId(),
                request.fcmToken(),
                request.platform(),
                request.deviceModel(),
                request.osVersion(),
                request.appVersion()
        );

        return ResponseEntity.ok(DeviceResponse.from(device));
    }

    /**
     * Revoke a single device — typically called on "log out on this device".
     */
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID deviceId,
            AuthenticatedUser user) {

        requireAuth(user);
        var revoked = registrationService.revoke(deviceId, user.userId(), "USER_LOGOUT");
        if (revoked.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Revoke every active token for this user — used on "log out everywhere"
     * or on account compromise recovery.
     */
    @DeleteMapping
    public ResponseEntity<Void> revokeAll(AuthenticatedUser user) {
        requireAuth(user);
        var count = registrationService.revokeAllForUser(user.userId(), "USER_LOGOUT_ALL");
        log.info("User {} revoked {} devices", user.userId(), count);
        return ResponseEntity.noContent().build();
    }

    private void requireAuth(AuthenticatedUser user) {
        if (user == null || user.userId() == null) {
            throw new UnauthorizedException("Authentication required");
        }
    }
}
