package com.stornuage.notification.repository;

import com.stornuage.notification.domain.ProcessedSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface ProcessedSignalRepository extends JpaRepository<ProcessedSignal, String> {

    /** Housekeeping: drop idempotency records older than 24h. */
    @Modifying
    @Query("DELETE FROM ProcessedSignal p WHERE p.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
