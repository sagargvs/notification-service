-- V1: Notification service schema
-- Separate schema to keep blast radius limited from the rest of the app

CREATE SCHEMA IF NOT EXISTS stornuage_notifications;
SET search_path TO stornuage_notifications;

-- =============================================================
-- user_devices: FCM tokens for push delivery
-- =============================================================
CREATE TABLE user_devices (
    device_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             VARCHAR(64) NOT NULL,
    fcm_token           TEXT NOT NULL,
    platform            VARCHAR(16) NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    device_model        VARCHAR(128),
    os_version          VARCHAR(32),
    app_version         VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ,
    revoke_reason       VARCHAR(64)
);

-- One active row per (user, token). A user can have multiple devices.
-- Uniqueness is on active rows only — revoked tokens can be "recycled"
CREATE UNIQUE INDEX ux_user_devices_active_token
    ON user_devices (user_id, fcm_token)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_user_devices_user_active
    ON user_devices (user_id)
    WHERE revoked_at IS NULL;

-- =============================================================
-- alert_state: one row per (container, alert_type) pair
--
-- State machine:
--   NONE  →  ACTIVE  →  ACKNOWLEDGED  →  (24h)  →  ACTIVE (re-fires)
--                    →  RESOLVED (by refill or item change)
--                    →  SUPPRESSED (during cooldown)
--                    →  ESCALATED (severity upgrade in place)
-- =============================================================
CREATE TABLE alert_state (
    alert_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 VARCHAR(64) NOT NULL,
    container_id            VARCHAR(64) NOT NULL,
    alert_type              VARCHAR(32) NOT NULL
        CHECK (alert_type IN ('FILL_CRITICAL', 'DEPLETION_WARNING', 'BATTERY_LOW')),
    severity                VARCHAR(16) NOT NULL
        CHECK (severity IN ('WARNING', 'CRITICAL')),
    state                   VARCHAR(16) NOT NULL
        CHECK (state IN ('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED')),
    item_name               VARCHAR(128),
    item_display_name       VARCHAR(128),
    metric_payload          JSONB,
    escalation_level        INT NOT NULL DEFAULT 1,
    first_fired_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_fired_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_notification_sent_at TIMESTAMPTZ,
    acknowledged_at         TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    resolved_reason         VARCHAR(64),
    -- optimistic locking column; every update increments this
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Only one active alert per (container, alert_type) at any time.
-- Resolved alerts accumulate as history.
CREATE UNIQUE INDEX ux_alert_state_active_per_type
    ON alert_state (container_id, alert_type)
    WHERE state IN ('ACTIVE', 'ACKNOWLEDGED');

CREATE INDEX ix_alert_state_user
    ON alert_state (user_id, state);

CREATE INDEX ix_alert_state_container
    ON alert_state (container_id);

CREATE INDEX ix_alert_state_history_lookup
    ON alert_state (user_id, created_at DESC);

-- =============================================================
-- notification_delivery_log: every FCM attempt, for audit + retry
-- =============================================================
CREATE TABLE notification_delivery_log (
    delivery_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id                UUID NOT NULL REFERENCES alert_state(alert_id),
    user_id                 VARCHAR(64) NOT NULL,
    device_id               UUID NOT NULL REFERENCES user_devices(device_id),
    fcm_message_id          VARCHAR(256),
    status                  VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DROPPED')),
    error_code              VARCHAR(64),
    error_message           TEXT,
    retry_count             INT NOT NULL DEFAULT 0,
    notification_title      VARCHAR(256),
    notification_body       VARCHAR(512),
    sent_at                 TIMESTAMPTZ,
    failed_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_delivery_log_alert
    ON notification_delivery_log (alert_id);

CREATE INDEX ix_delivery_log_user_recent
    ON notification_delivery_log (user_id, created_at DESC);

CREATE INDEX ix_delivery_log_status
    ON notification_delivery_log (status, created_at);

-- =============================================================
-- processed_signals: idempotency store for SQS message dedup
-- Prevents double-processing on visibility-timeout redelivery
-- =============================================================
CREATE TABLE processed_signals (
    signal_id       VARCHAR(128) PRIMARY KEY,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Keep only 24h of idempotency records; older signals can't be redelivered
CREATE INDEX ix_processed_signals_cleanup
    ON processed_signals (received_at);

-- =============================================================
-- Audit log of every state transition — useful for debugging
-- =============================================================
CREATE TABLE alert_state_audit (
    audit_id        BIGSERIAL PRIMARY KEY,
    alert_id        UUID NOT NULL,
    from_state      VARCHAR(16),
    to_state        VARCHAR(16) NOT NULL,
    from_severity   VARCHAR(16),
    to_severity     VARCHAR(16),
    trigger_signal  VARCHAR(128),
    reason          TEXT,
    actor           VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_audit_alert ON alert_state_audit (alert_id);
CREATE INDEX ix_audit_time ON alert_state_audit (occurred_at DESC);
