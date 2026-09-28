package com.stornuage.notification.domain;

/**
 * Persistent states of an alert.
 *
 * Note: "suppression" (deciding to not fire a notification within cooldown
 * or while an ignored escalation is maxed out) is a per-signal decision
 * logged in the audit table, NOT a persistent state. An alert whose
 * notification we chose not to send stays ACTIVE.
 */
public enum AlertState {
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED
}
