package com.stornuage.notification.repository;

import com.stornuage.notification.domain.AlertStateAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertStateAuditRepository extends JpaRepository<AlertStateAudit, Long> {

    List<AlertStateAudit> findByAlertIdOrderByOccurredAtAsc(UUID alertId);
}
