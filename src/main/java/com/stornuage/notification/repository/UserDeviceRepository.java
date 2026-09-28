package com.stornuage.notification.repository;

import com.stornuage.notification.domain.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

    /**
     * Find the active device record for a (user, token) pair.
     * Partial unique index guarantees at most one active row.
     */
    @Query("""
        SELECT d FROM UserDevice d
        WHERE d.userId = :userId
          AND d.fcmToken = :fcmToken
          AND d.revokedAt IS NULL
        """)
    Optional<UserDevice> findActiveByUserAndToken(
            @Param("userId") String userId,
            @Param("fcmToken") String fcmToken);

    /** All active devices under a customer — used for notification fan-out. */
    @Query("""
        SELECT d FROM UserDevice d
        WHERE d.customerId = :customerId
          AND d.revokedAt IS NULL
        """)
    List<UserDevice> findActiveByCustomerId(@Param("customerId") String customerId);

    /** All active devices for a specific user — used for per-user revocation. */
    @Query("""
        SELECT d FROM UserDevice d
        WHERE d.userId = :userId
          AND d.revokedAt IS NULL
        """)
    List<UserDevice> findActiveByUserId(@Param("userId") String userId);

    /** Count registrations in recent window — used for rate-limit check. */
    @Query("""
        SELECT COUNT(d) FROM UserDevice d
        WHERE d.customerId = :userId
          AND d.createdAt > :since
        """)
    long countRecentRegistrations(
            @Param("userId") String userId,
            @Param("since") java.time.Instant since);
}
