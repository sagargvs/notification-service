package com.stornuage.notification.service;

import com.stornuage.notification.domain.ProcessedSignal;
import com.stornuage.notification.repository.ProcessedSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency gate for signals.
 *
 * Extracted into its own bean because Spring's @Transactional is proxy-based:
 * self-invocation (calling @Transactional method from within the same class)
 * bypasses the proxy, silently dropping the transaction. By putting this in
 * a separate Spring-managed bean, the call from SignalProcessor goes through
 * the proxy and the transaction semantics actually apply.
 *
 * REQUIRES_NEW: the idempotency marker must persist even if downstream work
 * fails — otherwise a crash mid-processing would leave no record and the
 * next redelivery would create a duplicate alert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyGate {

    private final ProcessedSignalRepository processedSignalRepository;

    /**
     * Atomically record that this signalId has been seen.
     *
     * @return true if this is the first time we've seen this signalId;
     *         false if we've already processed it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markIfUnseen(String signalId) {
        try {
            processedSignalRepository.save(ProcessedSignal.builder()
                    .signalId(signalId)
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Signal {} already processed (PK conflict)", signalId);
            return false;
        }
    }
}
