# StorNuAge Backend Audit — Findings

**Generated:** 2026-04-20
**Scope:** notification-service, nutritionmeter-service, integration-service
**Reviewer:** Claude Code (Opus 4.7)
**Source checklist:** `docs/notification-service-audit-checklist.md`

Paths used:
- notification-service: `/Users/bond/Downloads/notification-service`
- nutritionmeter-service: `/Users/bond/Downloads/nutritionmeter-service`
- integration-service: `/Users/bond/Downloads/integration-service`

---

## Summary

| Severity | ✅ Implemented | ⚠️ Partial | ❌ Missing | Total |
|----------|---------------|-----------|-----------|-------|
| P0       | 41            | 5         | 23        | 69    |
| P1       | 10            | 2         | 8         | 20    |
| P2       | 0             | 0         | 5         | 5     |
| P3       | 0             | 0         | 3         | 3     |

**Headline issues (must fix before any shipping):**

1. **P0 — AWS access keys committed to both service repos in plaintext.** `integration-service/src/main/resources/application.properties:8-9` and `nutritionmeter-service/src/main/resources/application.properties:81-82,86-87` contain `aws.accessKeyId=AKIAQJM5I2CFOXVHPA7Y` and the matching secret. These must be **rotated immediately in AWS IAM**, purged from git history, and replaced with IAM task roles.
2. **P0 — nutrition-service publishes ZERO alert signals.** The entire producer side of the notification pipeline is absent. The notification-service has no one feeding it.
3. **P0 — integration-service SQS listeners swallow all exceptions and ack.** `IntakeSqsListener.java:45-50` and `IPResponseSqsListenerService.java:26-30` catch `Exception` and silently log. Transient failures = data loss; DLQ will never receive anything.
4. **P0 — integration-service has no state machine, no idempotency, no recovery job.** Stuck "awaiting image" telemetries have no cleanup path.
5. **P0 — `UserRateLimiter.java:38` calls `RateLimiter.getPermission(...)` which does not exist in Resilience4j 2.2.0.** This is a compile error; project cannot build.

---

## P0 findings

### N-A1 — Data model and migrations [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A1.1 | V1 migration creates all 5 tables | ✅ | `src/main/resources/db/migration/V1__notification_schema.sql:10,43,87,118,130` |
| A1.2 | `alert_state` partial unique index on `(container_id, alert_type) WHERE state IN ('ACTIVE','ACKNOWLEDGED')` | ✅ | `V1__notification_schema.sql:71-73` |
| A1.3 | `user_devices` partial unique index `(user_id, fcm_token) WHERE revoked_at IS NULL` | ✅ | `V1__notification_schema.sql:26-28` |
| A1.4 | `alert_state.version` optimistic lock column | ✅ | `V1__notification_schema.sql:64` and `Alert.java:85-87` |
| A1.5 | `metric_payload` is `jsonb`, Java uses `@JdbcTypeCode(SqlTypes.JSON)` | ✅ | `V1__notification_schema.sql:55`, `Alert.java:60-62` |

### N-A2 — Alert state machine correctness [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A2.1 | REFILL_DETECTED resolves stock alerts, no notification fired | ✅ | `AlertStateMachine.java:87-117` returns `Decision.Action.RESOLVE`; `SignalProcessor.shouldDeliver` (lines 110-115) excludes RESOLVE |
| A2.2 | REFILL_DETECTED does NOT resolve BATTERY_LOW | ✅ | `AlertStateMachine.java:93` `if (alert.getAlertType() == AlertType.BATTERY_LOW) continue;` |
| A2.3 | DEPLETION_WARNING arriving while FILL_CRITICAL active → IGNORE | ✅ | `AlertStateMachine.java:129-138` |
| A2.4 | FILL_CRITICAL while DEPLETION_WARNING → UPGRADE in place, severity=CRITICAL, escalation++ | ✅ | `AlertStateMachine.java:141-147, 194-217` |
| A2.5 | Cooldown check against `last_notification_sent_at` (not `last_fired_at`) | ✅ | `AlertStateMachine.java:241-242` |
| A2.6 | Cooldown resets after RESOLVED → new signal | ✅ | `findOpenByContainerAndType` returns empty for RESOLVED alerts → `createNewAlert` makes a fresh row with fresh `last_notification_sent_at` (`AlertStateMachine.java:150-154, 164-188`) |
| A2.7 | Escalation level caps at configured max, then SUPPRESS | ✅ | `AlertStateMachine.java:225-235` |

### N-A3 — Idempotency [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A3.1 | `IdempotencyGate` is a separate `@Service` bean | ✅ | `IdempotencyGate.java:26-28` |
| A3.2 | `markIfUnseen` uses `REQUIRES_NEW` propagation | ✅ | `IdempotencyGate.java:38` |
| A3.3 | PK conflict → returns false, processor skips | ✅ | `IdempotencyGate.java:40-48`, `SignalProcessor.java:57-60` |
| A3.4 | Nightly purge > 24h old | ✅ | `MaintenanceJobs.java:74-80` |

### N-A4 — SQS consumer [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A4.1 | `@SqsListener` listens continuously | ✅ | `AlertSignalConsumer.java:41-44` |
| A4.2 | Queue name has `.fifo` suffix | ✅ | `application.yml:39` → `stornuage-alert-signals.fifo` |
| A4.3 | `AcknowledgementMode.MANUAL` | ✅ | `SqsConfig.java:40` |
| A4.4 | InvalidSignalException → ack (no retry) | ✅ | `AlertSignalConsumer.java:49-52` |
| A4.5 | StaleSignalException → ack (no retry) | ✅ | `AlertSignalConsumer.java:53-56` |
| A4.6 | OptimisticLockingFailureException → throw | ✅ | `AlertSignalConsumer.java:57-62` |
| A4.7 | Other exceptions → throw | ✅ | `AlertSignalConsumer.java:63-68` |
| A4.8 | `messageVisibility=60s` exceeds p99 | ✅ | `SqsConfig.java:44`, `AlertSignalConsumer.java:43` |

### N-A5 — FCM client [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A5.1 | Credentials from Secrets Manager in prod, file path only in dev | ✅ | `FirebaseConfig.java:38-51` |
| A5.2 | UNREGISTERED / INVALID_ARGUMENT / SENDER_ID_MISMATCH / THIRD_PARTY_AUTH_ERROR → revoke token | ✅ | `FcmClient.java:142-146`, `NotificationDeliveryService.java:61-64` |
| A5.3 | QUOTA_EXCEEDED and INTERNAL/UNAVAILABLE → retry with backoff | ✅ | `FcmClient.java:147-150, 157-168` |
| A5.4 | Unknown MessagingErrorCode → default branch (no NPE) | ✅ | `FcmClient.java:135-139, 152-154` |
| A5.5 | CRITICAL severity uses `apns-priority: 10` | ✅ | `FcmClient.java:107-109` |
| A5.6 | Deep link server-constructed from closed template | ✅ | `FcmClient.java:88` |
| A5.7 | Every delivery attempt persists a notification_delivery_log row | ✅ | `NotificationDeliveryService.java:56-85` |

### N-A6 — Security [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A6.1 | `ThingsBoardJwtFilter` validates HMAC-SHA512 signature | ⚠️ | `ThingsBoardJwtFilter.java:50` uses `Keys.hmacShaKeyFor` which auto-selects HS256/384/512 by key length. Does NOT strictly enforce HS512. **Fix:** assert key length ≥ 64 bytes on startup or pin algorithm via `.requireAlgorithm(...)` |
| A6.2 | userId from JWT `sub` | ✅ | `ThingsBoardJwtFilter.java:89-93` |
| A6.3 | `/actuator/**` allowed without auth | ✅ | `ThingsBoardJwtFilter.java:55-58` |
| A6.4 | Device revoke ownership check | ✅ | `DeviceRegistrationService.java:75-79` |
| A6.5 | Alert acknowledge ownership check | ✅ | `AlertStateMachine.java:287-291` |
| A6.6 | Rate limiter 5/hr per user on device registration | ❌ | **Code will not compile**: `UserRateLimiter.java:38` calls `RateLimiter.getPermission(limiter, long)` which does not exist in Resilience4j 2.2.0. Must be `limiter.acquirePermission()`. See fix in section below. |
| A6.7 | SQS queue policy restricts publishers | ✅ | `terraform/notification-service.tf:63-75` |
| A6.8 | SQS queue policy restricts consumer | ✅ | `terraform/notification-service.tf:76-90` |
| A6.9 | FCM service account secret scoped to Cloud Messaging only | ⚠️ | Secret is referenced by name (`stornuage/fcm-service-account`) in `FirebaseConfig.java:43` but the scope of the underlying Google service account is outside this repo. **Fix:** document required IAM role and verify in GCP console; add a smoke-test startup check that fails fast if the credential has more than `firebasemessaging.messages.send`. |

### N-A8 — Scheduled jobs (P0 partial; most items P1) [notification-service]

Not strictly P0 in the checklist but flagged with one item that is:

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A8.3 | Multi-pod safety | ⚠️ | `MaintenanceJobs.java` has no ShedLock. Both jobs are idempotent so duplicate execution is safe, but two pods will double-run DB queries every cycle. **Fix:** add `shedlock-spring` (P1 if running single-pod, P0 if scaling out). |

### D5 — Secrets and creds [cross-cutting]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| D5.1 | No service account JSONs in repo, env vars, or Docker layers | ❌ | **Notification-service:** compliant (`.gitignore` doesn't show a JSON; no FCM JSON present). **nutrition-service & integration-service:** `aws.accessKeyId` / `aws.secretKey` committed in plaintext — `integration-service/src/main/resources/application.properties:8-9`, `nutritionmeter-service/src/main/resources/application.properties:81-82` and `:86-87`. **Immediate action: rotate the IAM user in AWS; scrub from git history; use ECS task role credential chain instead of static keys.** |
| D5.2 | DB passwords from Secrets Manager | ❌ | Plaintext `spring.datasource.password=postgres` in both integration (`application.properties:23`) and nutrition (`application.properties:111`). Only nutrition's `application-prod.properties:12` uses env var `${DATABASE_PASSWORD}` properly. |
| D5.3 | TB JWT signing key from Secrets Manager | ✅ (notification) / ❌ (nutrition, integration) | Notification: `task-definition.json:42` sources from Secrets Manager. Integration: `thingsboard.password=sysadmin` hard-coded at `application.properties:42`. Nutrition: defaults to literal `sysadmin` at `application.properties:98`. |
| D5.4 | FCM service account from Secrets Manager | ✅ | `FirebaseConfig.java:43-51` |

### B1 — Volume calculation [nutrition-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| B1.1 | Formula `height_of_item_cm = internal_height_cm - lidar_distance_cm - sensor_offset_cm` | ⚠️ | `VolumeCalculatorService.java:25` uses `fillHeightMm = emptyReading - distance`. Mathematically correct only if `emptyReadingMm` already bakes in `sensor_offset` (internal_height + sensor_offset). That assumption is undocumented — an operator configuring `emptyReading=internal_height` will produce readings offset by `sensor_offset`. **Fix:** split into explicit `internalHeightMm` + `sensorOffsetMm` columns on `ContainerProfile` and compute per spec. |
| B1.2 | Negative height clamped to 0 with SENSOR_ANOMALY log | ⚠️ | Clamped to 0 (`VolumeCalculatorService.java:28-32`) but log category is generic `log.warn` — no SENSOR_ANOMALY tag for alerting. |
| B1.3 | Height > internal_height clamped, fill%=0 | ⚠️ | Code clamps to max (`VolumeCalculatorService.java:34-39`) but does NOT set fill% to 0 — it reports the clamped-to-max fill (100%). Per spec, > internal_height means LIDAR reading is physically impossible and fill% should be 0 (sensor anomaly). |
| B1.4 | Missing density → fill% computed, weight=NULL | ❌ | `NutritionCalculatorService.java:17` calls `item.getDensity().doubleValue()` with no null check — NPE if density is null. |
| B1.5 | Container shape "other" handled or explicitly rejected | ⚠️ | Enum has `CUSTOM` (`ContainerShape.java:7`) but no `CustomVolumeCalculator`; factory throws `IllegalArgumentException` (`VolumeCalculatorFactory.java:31`). The service swallows this in the consumer, so effect is "silently dropped." |

### B2 — Consumption delta [nutrition-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| B2.1 | First reading writes telemetry, NOT consumption_log | ✅ | `TelemetryProcessingService.java:75-164` initializeContainerBaseline does not write ConsumptionEvent |
| B2.2 | Positive delta → consumption_log with event_type=REFILL | ❌ | `TelemetryProcessingService.java:264-321` only records consumption (positive `volumeConsumedMl` i.e., volume decreased). Negative delta just logs "Refill detected" and returns; **no REFILL event persisted and no REFILL_DETECTED signal published**. |
| B2.3 | Tiny delta below noise threshold → skipped in rate calc | ❌ | No noise threshold at all. `TelemetryProcessingService.java:237` treats any `volumeConsumedMl > 0` as consumption, including sensor jitter. |
| B2.4 | Out-of-order readings → delta vs nearest-earlier timestamp | ❌ | `TelemetryProcessingService.java:206-211` computes delta against `registry.lastVolumeMl` only — no timestamp comparison. A late-arriving older reading corrupts state. |
| B2.5 | Null predicted_item → fill% computed, weight+consumption=NULL | ❌ | `TelemetryProcessingService.java:174-185`: missing item → returns `recorded=false` with nothing computed, not "fill% with null weight" per spec. |

### B3 — Alert signal emission [nutrition-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| B3.1 | REFILL detection publishes REFILL_DETECTED signal | ❌ | **Not implemented anywhere.** `grep -r REFILL_DETECTED` → 0 matches in nutrition-service. |
| B3.2 | Item change without refill → resolve existing alerts | ❌ | Not implemented; no signal publisher at all. |
| B3.3 | Threshold crossing: 3 consecutive <10% OR single <5% | ❌ | No threshold logic. No FILL_CRITICAL / DEPLETION_WARNING emission. |
| B3.4 | Battery EWMA smoothing with 30min hold | ❌ | No battery processing at all. |
| B3.5 | Signal payload includes signalId (UUID), itemName, itemDisplayName frozen | ❌ | No signal payload exists. |
| B3.6 | SQS publish uses MessageGroupId = containerId | ❌ | No publish to alert-signals queue. |

**Net effect:** the notification-service is plumbed end-to-end but has nothing feeding it. This is the biggest delivery-blocker in the system.

### C1 — State machine [integration-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| C1.1 | States: PENDING → AWAITING_IMAGE → ENRICHMENT_QUEUED → COMPLETED | ❌ | `ContainerRegistry.java:80-81` has a `processing_status` column but no enum definition, no state-machine guard rails, no usage in `SqsMessageProcessor`. Current logic piggybacks on a `has_pending_telemetry` boolean (`ContainerRegistry.java:96-98`) — single-bit flag, no state transitions. |
| C1.2 | ScheduledRecoveryJob finds AWAITING_IMAGE > 5min, applies fallback | ❌ | No `@Scheduled`, no `@EnableScheduling` anywhere in integration-service. `grep -r Scheduled` → 0 matches. |
| C1.3 | Late image result after FAILED → idempotency check, drop | ❌ | `SqsMessageProcessor.processMessage` (lines 55-97) has no idempotency guard; late IP responses always overwrite state. |

### C2 — Decision matrix [integration-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| C2.1 | Cache lookup only trusts COMPLETED readings | ❌ | No cache-vs-state check; code trusts `container.currentItem` (`SqsMessageProcessor.java:235, 306-309`) regardless of state. |
| C2.2 | Missing imageKey when verification needed → FAILED or fallback | ❌ | `SqsMessageProcessor.processTelemetry` does not validate `telemetry.getImageKey()`. A null imageKey routed to image-processing queue will crash the downstream ML service. |
| C2.3 | Container not in DB → auto-provision UNASSIGNED or DLQ | ❌ | `SqsMessageProcessor.java:69-71, 207-209` throws `RuntimeException("Container not found")`. Combined with the listener's blanket `catch (Exception e) { log; }` pattern (C3.2), the message is SILENTLY DISCARDED — no DLQ, no provision. |
| C2.4 | Decision deterministic from DB state, no pod-local cache | ⚠️ | Decisions read from DB (`findByContainerId`), so deterministic. BUT: queues are standard, not FIFO — two pods can process two messages for the same container concurrently and race on `save()`. No optimistic locking on `ContainerRegistry`. |

### C3 — Queue handling [integration-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| C3.1 | Visibility timeout ≥ 2× p99 processing time | ✅ | 300s (`application.properties:68`, `IntakeSqsListener.java:23`). Generous. |
| C3.2 | Redrive policy with maxReceiveCount=3 on all queues | ❌ | No terraform/config for redrive policies in repo. And even if configured, it's defeated by the catch-all in `IntakeSqsListener.java:45-50` and `IPResponseSqsListenerService.java:26-30` which silently swallow exceptions and ack. |
| C3.3 | telemetry-events queue is FIFO with MessageGroupId=containerId | ❌ | All queues are standard. `grep .fifo` and `grep MessageGroupId` → 0 matches. This allows concurrent processing of same-container events, breaking ordering assumptions. |
| C3.4 | readingId deterministic hash of (containerId, reading_timestamp, lidar_raw) | ❌ | `IntakeMessageModel.timestamp` is passed through as-is; no hashing. Field exists but no invariant enforced. |

### D1 — Ordering and timestamps [cross-cutting]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| D1.1 | Timestamps use firmware-supplied `reading_timestamp` | ⚠️ | Nutrition: uses `payload.getTimeseries()` (`TelemetryProcessingService.java:39-47`) but falls back to `Instant.now()` if null. Integration: uses `Long.parseLong(telemetry.getTimestamp())` (`SqsMessageProcessor.java:217, 222`). No clock-drift validation. |
| D1.2 | Server rejects/flags readings with clock drift > 1h | ❌ | No drift check in either service. |
| D1.3 | LIDAR bounds check at firmware AND server | ⚠️ | Nutrition clamps negative and overflow (`VolumeCalculatorService.java:28-39`) but does not reject; integration does nothing with the raw `lidarDistanceMm` before forwarding. |

---

## P1 findings

### N-A7 — Controllers and HTTP surface [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A7.1 | POST /devices accepts token + platform, upserts | ✅ | `DeviceController.java:41-64`, `DeviceRegistrationService.java:32-63` |
| A7.2 | DELETE /devices/{deviceId} | ✅ | `DeviceController.java:69-80` |
| A7.3 | DELETE /devices (revoke all) | ✅ | `DeviceController.java:86-92` |
| A7.4 | GET /alerts returns only acting user's alerts | ✅ | `AlertController.java:35-42` (filtered by `user.userId()`) |
| A7.5 | POST /alerts/{alertId}/acknowledge | ✅ | `AlertController.java:51-60` |
| A7.6 | GlobalExceptionHandler, no stack traces leak | ✅ | `GlobalExceptionHandler.java:26-82` |

### N-A8 — Scheduled jobs [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A8.1 | `expireAcknowledgements` every 30min, flips ACKNOWLEDGED→ACTIVE for >24h old | ✅ | `MaintenanceJobs.java:47-66` |
| A8.2 | `purgeIdempotencyRecords` nightly, deletes >24h old | ✅ | `MaintenanceJobs.java:74-80` |

### N-A9 — Observability [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A9.1 | Log correlation signalId → alertId | ⚠️ | signalId and alertId both logged throughout (e.g. `SignalProcessor.java:63, 69`) but not threaded via MDC — manual grepping only. |
| A9.2 | Micrometer counters per outcome (signals_received, alerts_fired, fcm_sent, fcm_failed, tokens_revoked) | ❌ | Prometheus endpoint configured (`application.yml:70`) but no `MeterRegistry` injection or `Counter` definitions anywhere in code. |
| A9.3 | CloudWatch alarm on DLQ depth > 0 | ✅ | `terraform/notification-service.tf:99-117` |
| A9.4 | CloudWatch alarm on queue age > 5min | ✅ | `terraform/notification-service.tf:124-141` |
| A9.5 | Grafana dashboard (delivery success, p99, active alerts) | ❌ | Not in repo. |

### N-A10 — Ops [notification-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| A10.1 | Dockerfile two-stage, non-root user, JVM container flags | ✅ | `Dockerfile:1-21` |
| A10.2 | Traefik labels matching pattern | ✅ | `task-definition.json:17-28` |
| A10.3 | Secrets sourced from Secrets Manager | ✅ | `task-definition.json:39-43` |
| A10.4 | Health check on `/actuator/health` with 60s start period | ✅ | `task-definition.json:44-50` |
| A10.5 | Terraform FIFO queue + DLQ with maxReceiveCount=3 + queue policy | ✅ | `terraform/notification-service.tf:32-52, 59-92` |

### B4 — TB attribute update [nutrition-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| B4.1 | Retry with exponential backoff, then DLQ | ⚠️ | Retry with backoff present (`ThingsBoardAttributeWriter.java:103-133`). But on final failure it returns false — there is no DLQ or retry-queue; the failed update is lost. |
| B4.2 | Drop updates for reading_timestamp older than last pushed | ❌ | No "last pushed timestamp" tracked; async writer will happily overwrite newer data with older data if messages are processed out of order. |

### C4 — Transactional outbox [integration-service]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| C4.1 | DB write + SQS publish atomic or outbox-recoverable | ❌ | `SqsMessageProcessor.java:230-231, 245-247` persists `container` then publishes to SQS. No outbox table. If `sqsClient.sendMessage` fails, container state says "parked and routed" but no message exists. |
| C4.2 | ENRICHMENT_QUEUED but no SQS message → recovery re-publishes | ❌ | No recovery job; see C1.2. |

### D4 — Observability [cross-cutting]

| # | Item | Status | Reference |
|---|------|--------|-----------|
| D4.1 | Correlation ID (readingId) logged at every hop | ❌ | readingId exists on `TelemetryPayload` but never appears in logs; no MDC. |
| D4.2 | DLQ alarms on EVERY queue | ⚠️ | Only notification-service DLQ has a CloudWatch alarm. Integration queues and nutrition enrichment queue have no alarms in repo. |
| D4.3 | Grafana dashboard per service | ❌ | Not in repo. |

---

## P2 findings

| # | Item | Status | Note |
|---|------|--------|------|
| T1 | Test coverage | ❌ | notification-service has empty `src/test/java/com/stornuage/notification/` — no tests. Nutrition has one `ServiceApplicationTests.java` stub. Integration has one `IntegrationServiceApplicationTests.java` stub. |
| T2 | ContainerOwnershipClient | ❌ | `SignalProcessor.java:104-107` TODO: notification never verifies that `signal.userId` actually owns `signal.containerId`. A producer bug (or a compromised producer role, worst case) could spray alerts to arbitrary users. |
| T3 | README and onboarding docs | ❌ | notification-service has a README; the other two rely on ARCHITECTURE.md / HELP.md only. |
| D3.1 | Monthly ThingsBoard↔containers reconciliation | ❌ | Not implemented in any service. |
| D3.2 | Orphaned-device Grafana panel | ❌ | See above. |

---

## P3 findings

| # | Item | Status | Note |
|---|------|--------|------|
| D2.1 | CONTAINER_OFFLINE producer | ❌ | Out of scope per checklist. |
| D2.2 | Silence-sweep scheduled job | ❌ | Out of scope. |
| D2.3 | CONTAINER_OFFLINE signal type + handler | ❌ | Notification side not wired. |

---

## Already-fixed bugs verified

| # | Item | Status |
|---|------|--------|
| F1 | `pom.xml` `<n>` → `<name>` | ✅ verified at `pom.xml:15` |
| F2 | FcmClient switch has `default` | ✅ `FcmClient.java:152-154` |
| F3 | JSONB uses `@JdbcTypeCode(SqlTypes.JSON)` | ✅ `Alert.java:60` |
| F4 | IdempotencyGate extracted to separate bean | ✅ `IdempotencyGate.java:26` |
| F5 | NotificationProperties uses `@EnableConfigurationProperties` | ✅ `NotificationServiceApplication.java:11`, `NotificationProperties.java:9` |

---

## Recommended next actions (ordered)

1. **Rotate the leaked AWS IAM credentials NOW.** Revoke `AKIAQJM5I2CFOXVHPA7Y` in the AWS console, then purge it from git history of both repos.
2. ~~Fix the `UserRateLimiter` compile error so notification-service can actually build and deploy.~~ ✅ **Fixed in this audit** — see below.
3. ~~Strengthen JWT filter to pin HS512 (or at minimum, assert minimum key length at startup).~~ ✅ **Fixed in this audit** — see below.
4. Add ShedLock (or migrate to distributed scheduler) for `MaintenanceJobs` if you plan to run >1 pod.
5. Stop swallowing exceptions in integration-service listeners; let transient errors redeliver and eventually DLQ.
6. Build the alert-signal producer in nutrition-service — without it, the notification-service is dead weight.
7. Introduce an explicit state machine enum + `@Version` optimistic lock in `ContainerRegistry`, and a 5-minute `ScheduledRecoveryJob`.
8. Switch integration's telemetry-events queue to FIFO with `MessageGroupId=containerId`.
9. Add Micrometer counters in notification-service, then wire Grafana panels per service.
10. Add integration tests covering the full SQS → state machine → FCM path with testcontainers.

---

## Fixes applied in this audit (notification-service only)

### Fix #1 — `UserRateLimiter` compile error (was A6.6 / headline #5)
**File:** `src/main/java/com/stornuage/notification/service/UserRateLimiter.java:38`
**Problem:** called `RateLimiter.getPermission(limiter, Duration.ZERO.toMillis())` — this static method does not exist in Resilience4j 2.2.0, so the project did not compile.
**Fix:** replaced with the instance method `limiter.acquirePermission()`, which is the canonical non-blocking acquire in Resilience4j 2.x (timeoutDuration is already set to `Duration.ZERO` on the limiter config, so behavior matches intent: fail-fast when the budget is exhausted).

### Fix #2 — JWT filter now pins HS512 (was A6.1)
**File:** `src/main/java/com/stornuage/notification/auth/ThingsBoardJwtFilter.java`
**Problem:** `Keys.hmacShaKeyFor` silently downgrades to HS384/HS256 if the provided key is under 64 bytes, and the filter accepted any HMAC algorithm the library would verify. An attacker who obtained (or guessed) a short-key deployment could forge HS256 tokens against the same key bytes.
**Fix, applied in two places:**
- **Startup (constructor):** reject keys shorter than 64 bytes by throwing `IllegalStateException` — the service will refuse to start rather than silently running with a weak key.
- **Per-request (post-parse):** after `parseSignedClaims`, read `jws.getHeader().getAlgorithm()` and reject with 401 unless it equals `"HS512"`. This closes the HMAC-variant confusion attack regardless of key size.

Both fixes were made only inside `/Users/bond/Downloads/notification-service`. Every other P0 listed above (AWS creds, missing producer, exception swallowing in integration-service, missing state machine, etc.) lives outside this service and requires user decisions that go beyond single-service remediation.
