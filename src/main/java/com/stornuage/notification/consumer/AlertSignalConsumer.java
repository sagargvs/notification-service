package com.stornuage.notification.consumer;

import com.stornuage.notification.dto.AlertSignal;
import com.stornuage.notification.exception.InvalidSignalException;
import com.stornuage.notification.exception.StaleSignalException;
import com.stornuage.notification.service.SignalProcessor;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SQS listener for stornuage-alert-signals (FIFO).
 *
 * Listens continuously (long polling, 20s wait time), not a cron puller.
 * The Spring Cloud AWS SQS starter handles:
 *   - Long polling with receiveMessageWaitTimeSeconds = 20
 *   - Concurrent message processing with configurable thread count
 *   - Automatic ack on successful completion
 *   - Message return to queue on exception (redelivered up to maxReceiveCount,
 *     then DLQ per our queue's redrive policy)
 *
 * Ordering guarantee: FIFO queue + MessageGroupId = containerId means all
 * signals for a given container are processed serially by ONE consumer.
 * This prevents the race conditions we flagged in Hop 13 edge cases.
 *
 * Acknowledgement strategy:
 *   - On success or "poison" exceptions (InvalidSignal, StaleSignal):
 *     manually ack — the message will never succeed, retrying wastes resources.
 *   - On other exceptions: do NOT ack. SQS redelivers; after maxReceiveCount
 *     (configured per queue, recommend 3), message goes to DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertSignalConsumer {

    private final SignalProcessor processor;

    @SqsListener(value = "${aws.sqs.alert-signals-queue}", messageVisibilitySeconds = "60")
    public void onSignal(AlertSignal signal, Acknowledgement acknowledgement) {
        try {
            processor.process(signal);
            acknowledgement.acknowledge();
        } catch (InvalidSignalException e) {
            log.warn("Invalid signal {} — ack and drop: {}",
                    signal.getSignalId(), e.getMessage());
            acknowledgement.acknowledge();
        } catch (StaleSignalException e) {
            log.warn("Stale signal {} — ack and drop: {}",
                    signal.getSignalId(), e.getMessage());
            acknowledgement.acknowledge();
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // Another consumer updated the same alert. FIFO should make this rare.
            // Don't ack — SQS will redeliver and we'll re-evaluate.
            log.info("Optimistic lock conflict on signal {} — allowing SQS redelivery",
                    signal.getSignalId());
            throw e;
        } catch (Exception e) {
            // Transient — don't ack, SQS redelivers per redrive policy
            log.error("Transient failure processing signal {}: {}",
                    signal.getSignalId(), e.getMessage(), e);
            throw new RuntimeException("Signal processing failed", e);
        }
    }
}
