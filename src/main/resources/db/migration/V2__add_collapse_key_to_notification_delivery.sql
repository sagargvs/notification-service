ALTER TABLE stornuage_notifications.notification_delivery_log
    ADD COLUMN IF NOT EXISTS collapse_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_nd_user_device_collapse_key
    ON stornuage_notifications.notification_delivery_log (user_id, device_id, collapse_key)
    WHERE status = 'SENT';
