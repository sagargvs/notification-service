package com.stornuage.notification.repository;

import com.stornuage.notification.domain.DeliveryStatus;
import com.stornuage.notification.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationDeliveryRepository
        extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findByAlertId(UUID alertId);

    Optional<NotificationDelivery> findTopByUserIdAndDeviceIdAndCollapseKeyAndStatusOrderBySentAtDesc(
            String userId, UUID deviceId, String collapseKey, DeliveryStatus status);
}
