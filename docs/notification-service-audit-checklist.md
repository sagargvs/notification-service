# StorNuAge Backend Audit Checklist

**Purpose.** This file is a structured checklist for Claude Code (or any
reviewer) to audit the StorNuAge backend codebase against the designed
architecture in `notification-service-architecture.md`.

**How to use.**
1. Open this repo in Claude Code: `cd <repo-root> && claude`
2. Prompt Claude Code with:
   > Audit the backend services against `docs/notification-service-audit-checklist.md`.
   > For each item, verify the current code and mark findings as
   > ✅ implemented, ⚠️ partial, or ❌ missing. Include file:line references.
   > Produce `docs/audit-findings.md` grouped by severity.
3. Review findings, discuss, and iterate.

**Severity levels:**
- **P0** — data loss, security hole, or notification silent failure
- **P1** — correctness issue that manifests under load or rare inputs
- **P2** — observability gap, test gap, or minor code quality
- **P3** — future work, phase 2

---

## Section A — Notification service (this service)

### A1. Data model and migrations [P0]
- [ ] V1 migration creates all 5 tables (user_devices, alert_state, notification_delivery_log, processed_signals, alert_state_audit)
- [ ] `alert_state` partial unique index on `(container_id, alert_type) WHERE state IN ('ACTIVE','ACKNOWLEDGED')` — allows only one open alert per type per container
- [ ] `user_devices` partial unique index `(user_id, fcm_token) WHERE revoked_at IS NULL`
- [ ] `alert_state.version` column present (optimistic lock)
- [ ] `alert_state.metric_payload` is `jsonb` not `text`, and Java side uses `@JdbcTypeCode(SqlTypes.JSON)` not a string converter

### A2. Alert state machine correctness [P0]
- [ ] REFILL_DETECTED resolves all open stock alerts, never fires notification
- [ ] REFILL_DETECTED does NOT resolve BATTERY_LOW
- [ ] DEPLETION_WARNING arriving while FILL_CRITICAL active → IGNORE (subsumed)
- [ ] FILL_CRITICAL arriving while DEPLETION_WARNING active → UPGRADE in place, severity=CRITICAL, escalation_level++
- [ ] Cooldown check against `last_notification_sent_at`, not `last_fired_at`
- [ ] Cooldown resets when alert transitions RESOLVED → any new signal
- [ ] Escalation level caps at configured max; further signals SUPPRESS without notification

### A3. Idempotency [P0]
- [ ] `IdempotencyGate` is a separate `@Service` bean, NOT an inner method of SignalProcessor (Spring @Transactional self-invocation bug)
- [ ] `markIfUnseen` uses `REQUIRES_NEW` propagation
- [ ] Failure to insert processed_signal due to PK conflict → returns false, processor skips
- [ ] `processed_signals` has nightly purge > 24h old

### A4. SQS consumer [P0]
- [ ] `@SqsListener` listens continuously, not polled via cron
- [ ] Queue referenced by name is `.fifo` suffix (FIFO queue)
- [ ] `AcknowledgementMode.MANUAL` in SqsConfig
- [ ] InvalidSignalException → ack (no retry)
- [ ] StaleSignalException → ack (no retry)
- [ ] OptimisticLockingFailureException → throw (allow SQS redelivery)
- [ ] All other exceptions → throw (allow SQS redelivery then DLQ after maxReceiveCount=3)
- [ ] `messageVisibility=60s` exceeds p99 processing time

### A5. FCM client [P0]
- [ ] Credentials loaded from Secrets Manager in prod, file path only in dev
- [ ] UNREGISTERED and INVALID_ARGUMENT/SENDER_ID_MISMATCH/THIRD_PARTY_AUTH_ERROR → revoke token
- [ ] QUOTA_EXCEEDED and INTERNAL/UNAVAILABLE → retry with backoff
- [ ] Unknown MessagingErrorCode → default branch, UNKNOWN_ERROR (no NPE)
- [ ] CRITICAL severity uses `apns-priority: 10`
- [ ] Deep link is server-constructed from closed template, never from signal payload
- [ ] Every delivery attempt persists a notification_delivery_log row

### A6. Security [P0]
- [ ] `ThingsBoardJwtFilter` validates signature with HMAC-SHA512 key from env
- [ ] userId for every endpoint comes from JWT `sub`, not request body
- [ ] `/actuator/**` is allowed without auth
- [ ] Device revoke checks `device.userId == acting user` before updating
- [ ] Alert acknowledge checks `alert.userId == acting user`
- [ ] Rate limiter applied to device registration (5/hr per user)
- [ ] SQS queue policy restricts publishers to known producer role ARNs
- [ ] SQS queue policy restricts consumer to notification service role ARN
- [ ] FCM service account secret scoped to Cloud Messaging only

### A7. Controllers and HTTP surface [P1]
- [ ] `POST /api/v1/notifications/devices` accepts token + platform, upserts
- [ ] `DELETE /api/v1/notifications/devices/{deviceId}` revokes single
- [ ] `DELETE /api/v1/notifications/devices` revokes all (logout everywhere)
- [ ] `GET /api/v1/notifications/alerts` returns only the acting user's alerts
- [ ] `POST /api/v1/notifications/alerts/{alertId}/acknowledge` transitions state
- [ ] GlobalExceptionHandler maps all exceptions to proper HTTP status; no stack traces leak

### A8. Scheduled jobs [P1]
- [ ] `expireAcknowledgements` runs every 30min, flips ACKNOWLEDGED→ACTIVE for alerts >24h old
- [ ] `purgeIdempotencyRecords` runs nightly, deletes processed_signals >24h old
- [ ] Multi-pod safety: idempotent if both run (or use ShedLock for single-pod execution)

### A9. Observability [P1]
- [ ] Log correlation using signalId → alertId across hops
- [ ] Micrometer counters per outcome: signals_received, alerts_fired, fcm_sent, fcm_failed, tokens_revoked
- [ ] CloudWatch alarm on DLQ depth > 0
- [ ] CloudWatch alarm on queue age > 5min
- [ ] Grafana dashboard: delivery success rate, p99 processing time, active alerts count

### A10. Ops [P1]
- [ ] Dockerfile two-stage, non-root user, JVM container flags
- [ ] Task definition includes Traefik labels matching pattern of other services
- [ ] Task definition sources secrets from Secrets Manager, not env strings
- [ ] Health check on `/actuator/health` with 60s start period
- [ ] Terraform defines FIFO queue, DLQ with maxReceiveCount=3, and queue policy

---

## Section B — Nutrition Service (audit against spec)

### B1. Volume calculation [P0]
- [ ] Formula: `height_of_item_cm = internal_height_cm - lidar_distance_cm - sensor_offset_cm`
- [ ] Negative height clamped to 0 with SENSOR_ANOMALY log (edge 9.1)
- [ ] Height > internal_height clamped, fill% = 0 (edge 9.2)
- [ ] Missing density in items_catalog → fill% computed, weight=NULL (edge 9.3)
- [ ] Container shape "other" handled or explicitly rejected (edge 9.6)

### B2. Consumption delta [P0]
- [ ] First reading writes telemetry_readings, NOT consumption_log (edge 9.7)
- [ ] Positive delta → consumption_log with event_type=REFILL (edge 9.8)
- [ ] Tiny delta (below noise threshold) → skipped in consumption rate calc (edge 9.9)
- [ ] Out-of-order readings → delta computed against nearest-earlier timestamp (edge 9.11)
- [ ] Null predicted_item → fill% computed, weight+consumption=NULL (edge 9.12)

### B3. Alert signal emission [P0]
- [ ] REFILL detection publishes REFILL_DETECTED signal (edge 13.2)
- [ ] Item change without refill → resolve existing alerts, don't emit new ones (edge 13.5)
- [ ] Threshold crossing requires 3 consecutive <10% OR single <5% (edge 10.1)
- [ ] Battery EWMA smoothing with 30min hold (edge 10.2)
- [ ] Signal payload includes `signalId` (UUID), `itemName`, `itemDisplayName` frozen at detection time
- [ ] SQS publish uses MessageGroupId = containerId

### B4. TB attribute update [P1]
- [ ] Retry with exponential backoff, then DLQ (edge 11.1)
- [ ] Drop updates for reading_timestamp older than last pushed (edge 11.2)

---

## Section C — Integration Service (audit against spec)

### C1. State machine [P0]
- [ ] States: PENDING → AWAITING_IMAGE → ENRICHMENT_QUEUED → COMPLETED
- [ ] ScheduledRecoveryJob finds AWAITING_IMAGE > 5min and applies fallback
- [ ] Late image result arriving after FAILED → idempotency check, drop (edge 7.1)

### C2. Decision matrix [P0]
- [ ] Cache lookup only trusts COMPLETED readings (edge 4.2)
- [ ] Missing imageKey when verification needed → mark FAILED or fallback (edge 4.3)
- [ ] Container not in DB → auto-provision UNASSIGNED or DLQ (edge 4.1)
- [ ] Decision deterministic from DB state, no pod-local cache (edge 4.4)

### C3. Queue handling [P1]
- [ ] Visibility timeout ≥ 2×p99 processing time (edge 3.1)
- [ ] Redrive policy with maxReceiveCount=3 on all queues (edge 3.2)
- [ ] telemetry-events queue is FIFO with MessageGroupId=containerId (edge 3.3)
- [ ] readingId is deterministic hash of (containerId, reading_timestamp, lidar_raw) for idempotency

### C4. Transactional outbox [P1]
- [ ] DB write + SQS publish either atomic or recoverable via outbox pattern
- [ ] If ENRICHMENT_QUEUED but no message in queue, recovery job re-publishes (edge 8.1)

---

## Section D — Cross-cutting concerns

### D1. Ordering and timestamps [P0]
- [ ] All timestamp computation uses firmware-supplied reading_timestamp, never server receipt time (edge 1.2)
- [ ] Server rejects/flags readings with clock drift > 1h (edge 1.3)
- [ ] LIDAR bounds check at both firmware and server (edge 1.4)

### D2. Container offline detection [P3 — not yet built]
- [ ] Producer service for CONTAINER_OFFLINE signal
- [ ] Scheduled sweep of telemetry_readings for silence > 24h
- [ ] New signal type + handler in notification service

### D3. Reconciliation [P2]
- [ ] Monthly job matches ThingsBoard devices to containers table
- [ ] Orphans flagged on Grafana

### D4. Observability [P1]
- [ ] Correlation ID (readingId) logged at every hop
- [ ] DLQ depth alarms on EVERY queue, not just alert-signals
- [ ] Grafana dashboard per service with p99, error rate, queue depth

### D5. Secrets and creds [P0]
- [ ] No service account JSONs in repo, env vars, or Docker layers
- [ ] DB passwords from Secrets Manager
- [ ] TB JWT signing key from Secrets Manager
- [ ] FCM service account from Secrets Manager

---

## Bugs to verify in handoff code (from initial build)

Already fixed in the current skeleton but double-check:

- [x] ~~pom.xml `<n>` tag should be `<name>`~~ — **needs manual verify**
- [x] FcmClient switch has `default` branch
- [x] JSONB uses `@JdbcTypeCode(SqlTypes.JSON)` not AttributeConverter
- [x] IdempotencyGate extracted to separate bean (fixes self-invocation)
- [x] NotificationProperties uses `@EnableConfigurationProperties` pattern

## Completion artifact

Claude Code should produce `docs/audit-findings.md` with this structure:

```md
# StorNuAge Backend Audit — Findings

Generated: <date>
Scope: notification-service, nutrition-service, integration-service

## Summary
- Total items checked: N
- P0: X implemented, Y missing
- P1: X implemented, Y missing
- ...

## P0 findings

### A1-item-3 — user_devices partial unique index missing
**Status:** ❌ missing
**File:** services/notification-service/src/main/resources/db/migration/V1.sql:45
**Finding:** Index exists but lacks the `WHERE revoked_at IS NULL` clause.
Without this, re-registering after a revoke fails with uniqueness violation.
**Fix:** Add `WHERE revoked_at IS NULL` to the index definition.

...
```
