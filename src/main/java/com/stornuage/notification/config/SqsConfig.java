package com.stornuage.notification.config;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;

/**
 * Explicit SQS listener factory configuration.
 *
 * Key choices:
 *
 *   pollTimeout: 20s — long polling, reduces empty receives.
 *
 *   maxConcurrentMessages: 10 — we can process up to 10 signals in
 *   parallel per pod. FIFO queue + MessageGroupId=containerId means
 *   messages from the same container will always go through one thread
 *   regardless of this setting.
 *
 *   maxMessagesPerPoll: 10 — standard batch size.
 *
 *   acknowledgement: MANUAL — consumer decides when to ack based on
 *   the outcome (per-message ack already implemented in AlertSignalConsumer).
 *
 *   messageVisibility: 60s — must exceed p99 processing time. With FCM
 *   retries inside processing, 60s is a safe floor.
 */
@Configuration
public class SqsConfig {

    @Bean
    public SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory(
            SqsAsyncClient sqsAsyncClient) {

        return SqsMessageListenerContainerFactory.builder()
                .configure(options -> options
                        .acknowledgementMode(AcknowledgementMode.MANUAL)
                        .maxConcurrentMessages(10)
                        .maxMessagesPerPoll(10)
                        .pollTimeout(Duration.ofSeconds(20))
                        .messageVisibility(Duration.ofSeconds(60))
                )
                .sqsAsyncClient(sqsAsyncClient)
                .build();
    }
}
