package com.stornuage.notification.service;

import com.stornuage.notification.client.FcmClient.NotificationContent;
import com.stornuage.notification.config.NotificationProperties;
import com.stornuage.notification.domain.Alert;
import com.stornuage.notification.domain.AlertType;
import com.stornuage.notification.service.AlertStateMachine.Decision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class NotificationComposer {

    private final NotificationProperties properties;

    public NotificationContent composeBatch(List<Alert> alerts) {
        if (alerts.size() == 1) {
            return compose(alerts.get(0), Decision.Action.FIRE_NEW);
        }

        boolean allBattery = alerts.stream()
                .allMatch(a -> a.getAlertType() == AlertType.BATTERY_LOW);

        if (allBattery) {
            return new NotificationContent(
                    alerts.size() + " containers need charging",
                    "Tap to view",
                    "battery-status");
        }

        var title = alerts.size() + " items running low";
        String body;
        if (alerts.size() <= properties.getCoalescing().getMaxItemsInBody()) {
            body = alerts.stream()
                    .map(this::resolveItemLabel)
                    .collect(Collectors.joining(", "));
        } else {
            body = "Tap to view";
        }
        return new NotificationContent(title, body, "low-stock");
    }

    public NotificationContent compose(Alert alert, Decision.Action action) {
        var itemLabel = resolveItemLabel(alert);
        var base = switch (alert.getAlertType()) {
            case FILL_CRITICAL -> composeFillCritical(itemLabel, action);
            case DEPLETION_WARNING -> composeDepletion(itemLabel, action);
            case BATTERY_LOW -> composeBattery(itemLabel, action);
        };
        return new NotificationContent(base.title(), base.body(), "container/" + alert.getContainerId());
    }

    private NotificationContent composeFillCritical(String itemLabel, Decision.Action action) {
        var title = action == Decision.Action.FIRE_UPGRADE
                ? "Almost empty: " + itemLabel
                : "Running critically low: " + itemLabel;
        var body = "Your " + itemLabel + " is nearly out — time to restock";
        return new NotificationContent(title, body, null);
    }

    private NotificationContent composeDepletion(String itemLabel, Decision.Action action) {
        var title = itemLabel + " running low";
        var body = "About 2 days of " + itemLabel + " left. Add it to your shopping list?";
        return new NotificationContent(title, body, null);
    }

    private NotificationContent composeBattery(String itemLabel, Decision.Action action) {
        var title = "Container battery low";
        var body = itemLabel != null
                ? "The container holding " + itemLabel + " needs charging"
                : "One of your containers needs charging";
        return new NotificationContent(title, body, null);
    }

    private String resolveItemLabel(Alert alert) {
        if (alert.getItemDisplayName() != null && !alert.getItemDisplayName().isBlank()) {
            return alert.getItemDisplayName();
        }
        if (alert.getItemName() != null && !alert.getItemName().isBlank()) {
            return humanize(alert.getItemName());
        }
        return "a container";
    }

    private String humanize(String snakeCase) {
        var parts = snakeCase.split("_");
        var sb = new StringBuilder();
        for (var part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }
}
