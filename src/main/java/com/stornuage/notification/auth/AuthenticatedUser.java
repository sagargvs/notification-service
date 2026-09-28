package com.stornuage.notification.auth;

/**
 * The subset of ThingsBoard JWT claims this service cares about.
 *
 * ThingsBoard JWTs include much more (customerId, tenantId, scopes etc.)
 * but the notification service only needs the user identity.
 */
public record AuthenticatedUser(
        String userId,
        String email,
        String authority,
        String customerId
) {}
