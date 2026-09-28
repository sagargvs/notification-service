package com.stornuage.notification.controller;

import com.stornuage.notification.auth.AuthenticatedUser;
import com.stornuage.notification.dto.AlertResponse;
import com.stornuage.notification.exception.UnauthorizedException;
import com.stornuage.notification.repository.AlertRepository;
import com.stornuage.notification.service.AlertStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * In-app alert history and user-driven acknowledgement.
 *
 * History endpoint is used by the notifications screen in the app.
 * Acknowledge is typically called automatically when the user opens
 * the container detail screen after tapping a notification.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRepository alertRepository;
    private final AlertStateMachine stateMachine;

    /**
     * Returns the user's alert history, most recent first.
     * Alert rows for other users are never returned — filtered by
     * {@code user.userId()} from the JWT.
     */
    @GetMapping
    public List<AlertResponse> listMine(AuthenticatedUser user) {
        requireAuth(user);
        return alertRepository.findByUserIdOrderByCreatedAtDesc(user.userId())
                .stream()
                .map(AlertResponse::from)
                .toList();
    }

    /**
     * Mark an alert as acknowledged. Cooldown begins from this moment;
     * re-firing happens after the acknowledgement expiry window
     * (see AcknowledgementExpiryJob).
     *
     * Idempotent — acknowledging an already-acknowledged alert is a no-op.
     */
    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<AlertResponse> acknowledge(
            @PathVariable UUID alertId,
            AuthenticatedUser user) {

        requireAuth(user);
        var ack = stateMachine.acknowledge(alertId, user.userId());
        return ack.map(a -> ResponseEntity.ok(AlertResponse.from(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireAuth(AuthenticatedUser user) {
        if (user == null || user.userId() == null) {
            throw new UnauthorizedException("Authentication required");
        }
    }
}
