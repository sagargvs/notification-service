package com.stornuage.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private Firebase firebase = new Firebase();
    private Cooldown cooldown = new Cooldown();
    private Escalation escalation = new Escalation();
    private Signal signal = new Signal();
    private Fcm fcm = new Fcm();
    private Coalescing coalescing = new Coalescing();

    @Getter @Setter
    public static class Firebase {
        /** Name of secret in AWS Secrets Manager holding the service account JSON. */
        private String credentialsSecretName;
        /** Fallback: path to service account JSON on disk (dev only). */
        private String credentialsPath;
        /** Raw service account JSON passed as env var (Fargate). Takes top priority. */
        private String credentialsJson;
    }

    @Getter @Setter
    public static class Cooldown {
        /** No re-fire of the same alert within this window unless severity upgrades. */
        private int sameSeverityHours = 24;
    }

    @Getter @Setter
    public static class Escalation {
        /** Stop re-firing after this many re-sends. Alert remains ACTIVE silently. */
        private int maxLevel = 3;
    }

    @Getter @Setter
    public static class Signal {
        /** Reject signals older than this — stale-signal protection. */
        private int maxAgeMinutes = 10;
    }

    @Getter @Setter
    public static class Fcm {
        private int maxRetries = 3;
        private int retryBackoffMs = 500;
    }

    @Getter @Setter
    public static class Coalescing {
        private int windowSeconds = 90;
        private int maxItemsInBody = 3;
    }
}
