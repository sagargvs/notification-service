# Notification Service — Architecture & Edge Cases

This document is the design reference for the StorNuAge notification system.
It is cited by the audit checklist (`notification-service-audit-checklist.md`)
and should be treated as authoritative when discrepancies arise in code.

---

## 1. Where this fits in the StorNuAge stack

```
ESP32 container
  │ MQTT
  ▼
ThingsBoard (EC2 behind NLB, Elastic IP, devices/users/auth)
  │ Rule chain → SQS (stornuage-telemetry-events, FIFO by containerId)
  ▼
Integration Service (Fargate)
  │ State machine: PENDING → AWAITING_IMAGE → ENRICHMENT_QUEUED → COMPLETED
  ├─ ML path ──► Image Processor (Fargate) ──► image-results SQS
  └─ skip path ─────────────────────────────────────────────┐
                                                            ▼
                                             Integration Service merges result
                                                            │
                                                            │ enrichment SQS
                                                            ▼
                                               Nutrition Service (Fargate)
                                               - volume → weight → fill%
                                               - consumption_log delta
                                               - threshold check
                                                            │
                                                            ├─► RDS PostgreSQL
                                                            ├─► ThingsBoard REST (attributes → WS push)
                                                            └─► alert-signals SQS (FIFO by containerId)
                                                                         │
                                               Depletion Predictor ──────┤
                                               (Fargate, EventBridge 6h  │
                                                + reactive per-event)    │
                                                                         ▼
                                                           Notification Service (this service)
                                                           - FIFO SQS consumer
                                                           - AlertStateMachine (dedup, escalation)
                                                           - FCM → APNs/Android
                                                                         │
                                                                         ▼
                                                                    User device
```

## 2. Design principles

| # | Principle | Why |
|---|-----------|-----|
| 1 | One queue per concern, FIFO where order matters | Prevents race conditions at the consumer |
| 2 | Producers publish typed payloads; consumers validate | Decoupling + defense |
| 3 | Idempotency at the boundary | SQS redelivery, visibility timeouts, retries |
| 4 | Optimistic locking on mutable state | FIFO alone is not enough for multi-consumer scenarios |
| 5 | Security controls at every interface | JWT on HTTP, IAM on SQS, Secrets Manager for creds |
| 6 | Never trust producer-supplied identity | userId comes from JWT, not request body |
| 7 | Cross-service item metadata frozen at detection time | Avoids race between alert fire and state change |

## 3. Signal contract

Producers (Nutrition Service, Depletion Predictor) publish to
`stornuage-alert-signals.fifo` with this envelope:

```json
{
  "signalId": "uuid — producer generated, MUST be idempotent across retries",
  "signalType": "FILL_CRITICAL | DEPLETION_WARNING | BATTERY_LOW | REFILL_DETECTED",
  "containerId": "string",
  "userId": "string (from containers.user_id, authoritative)",
  "itemName": "snake_case_key (nullable for BATTERY_LOW when empty)",
  "itemDisplayName": "Human Friendly (nullable)",
  "metric": { /* type-specific, see below */ },
  "detectedAt": "ISO-8601 instant",
  "producerService": "string (optional, debug)"
}
```

**SQS message attributes required:**
- `MessageGroupId` = `containerId` (ordering)
- `MessageDeduplicationId` = `signalId` (FIFO 5-min dedup layer)

**Per-signal metric shapes:**

| signalType | metric |
|------------|--------|
| `FILL_CRITICAL`    | `{ "fillPercentage": 8.2 }` |
| `DEPLETION_WARNING`| `{ "daysRemaining": 1.6, "currentWeightGrams": 180 }` |
| `BATTERY_LOW`      | `{ "batteryPercentage": 12 }` |
| `REFILL_DETECTED`  | `{ "previousFillPercentage": 8, "newFillPercentage": 85 }` |

## 4. Alert state machine

Persistent states: `ACTIVE`, `ACKNOWLEDGED`, `RESOLVED`. SUPPRESSED is a
per-signal decision logged in the audit table, not a persistent state.

```
            signal arrives
                 │
                 ▼
           ┌─── ACTIVE ────────┐
           │     │             │
           │     │ user opens  │
           │     ▼             │
           │ ACKNOWLEDGED      │ refill detected
           │     │             │ or item changed
           │     │ 24h expiry  │
           │     ▼             │
           └──► ACTIVE         ▼
                           RESOLVED (terminal)
```

**Signal → Decision table:**

| Signal | Existing alert state | Action |
|--------|---------------------|--------|
| `FILL_CRITICAL` | none | CREATE new CRITICAL alert, FIRE |
| `FILL_CRITICAL` | ACTIVE `DEPLETION_WARNING` | UPGRADE to CRITICAL in place, FIRE |
| `FILL_CRITICAL` | ACTIVE `FILL_CRITICAL`, within cooldown | SUPPRESS (log only) |
| `FILL_CRITICAL` | ACTIVE `FILL_CRITICAL`, cooldown expired | RE-FIRE, escalation_level++ |
| `FILL_CRITICAL` | ACTIVE `FILL_CRITICAL`, escalation_level == max | SUPPRESS (stop nagging) |
| `DEPLETION_WARNING` | none | CREATE new WARNING alert, FIRE |
| `DEPLETION_WARNING` | ACTIVE `FILL_CRITICAL` | IGNORE (subsumed) |
| `DEPLETION_WARNING` | ACTIVE `DEPLETION_WARNING`, within cooldown | SUPPRESS |
| `DEPLETION_WARNING` | ACKNOWLEDGED `DEPLETION_WARNING` | wait for ack expiry job |
| `BATTERY_LOW` | independent of stock alerts | standard create-or-suppress |
| `REFILL_DETECTED` | any open stock alerts | RESOLVE all (no notification) |

Cooldown default: 24h between same-severity re-fires.
Escalation max default: 3.

## 5. 18-hop end-to-end flow

### Hop 1 — Physical event at container
Firmware detects LIDAR delta ≥ 0.5cm, captures image, publishes MQTT.

**Edge cases:**
1.1 Device power cycle mid-reading → orphan S3 image (lifecycle rule)
1.2 WiFi disconnect buffers readings → out-of-order arrival (sort by reading_timestamp)
1.3 Clock drift without NTP → reject if reading_timestamp > 1h from server
1.4 LIDAR garbage (-5cm, 999cm) → firmware + server bounds check
1.5 Lid open during reading → requires lid sensor OR N consecutive stable readings
1.6 Image upload fails but telemetry succeeds → AWAITING_IMAGE → FALLBACK
1.7 First-ever reading → force MANDATORY verification
1.8 Refill event (positive delta) → MANDATORY verification + REFILL signal

### Hop 2 — ThingsBoard ingestion
**Edge cases:**
2.1 Device credential expired → 401, container silent (need offline alert)
2.2 TB rule chain → SQS publish fails → TB-side DLQ needed, monitor with Grafana
2.3 SQS throttle at scale → TB rule chain must retry with backoff
2.4 Malformed telemetry → TB validates schema before publish
2.5 Deleted device still publishing → firmware needs permanent-failure state

### Hop 3 — telemetry-events SQS
**Edge cases:**
3.1 Visibility timeout too short → double processing; fix: visibility ≥ 2×p99
3.2 Poison pill → crash loop; fix: redrive policy maxReceiveCount=3
3.3 Message ordering → use FIFO with containerId as MessageGroupId

### Hop 4 — Integration Service TelemetryEventConsumer
**Edge cases:**
4.1 Container not in DB → auto-provision as UNASSIGNED, reconciliation job
4.2 Cache lookup race → only trust last COMPLETED reading, never AWAITING_IMAGE
4.3 Missing imageKey → mark FAILED(MISSING_IMAGE) or fallback to last known
4.4 Decision matrix disagreement → never use pod-local cache; DB state only
4.5 SQS publish to image queue fails → transactional outbox pattern

### Hop 5 — image-processing SQS
5.1 CV cold start → minimum task count 1 in prod
5.2 Queue backlog → autoscale on queue depth
5.3 Large images → visibility timeout 30s, log inference time

### Hop 6 — Image Processor (CV)
6.1 S3 image 404 → structured failure result, don't crash
6.2 Low confidence → publish result with predictedItem=null, LOW_CONFIDENCE reason
6.3 Item mismatch with last known → flag PREDICTION_CONFLICT, use last known
6.4 Model returns unknown class → versioned label set, catalog gate in CI
6.5 CV crash mid-inference → maxReceiveCount + ScheduledRecoveryJob fallback

### Hop 7 — image-results SQS
7.1 Late result arrives after state = FAILED → idempotency check, drop
7.2 Result for non-existent reading → log and drop
7.3 Duplicate ML result → conditional update on AWAITING_IMAGE state

### Hop 8 — Integration Service → enrichment queue → Nutrition Service
8.1 Publish fails after state change → transactional outbox OR recovery job
8.2 Partial enrichment message → schema validate, DLQ invalid

### Hop 9 — Nutrition Service volume calculation
```
height_of_item_cm = internal_height_cm - lidar_distance_cm - sensor_offset_cm
volume_cm3        = height_of_item_cm × cross_section_area_cm2
weight_grams      = volume_cm3 × density_g_per_cm3 (per-item)
fill_percentage   = height_of_item_cm / internal_height_cm × 100
```

**Edge cases:**
9.1 Negative height → clamp to 0, log SENSOR_ANOMALY
9.2 Height > internal_height → clamp fill_percentage to 0
9.3 No density for predicted_item → compute fill% only, weight=NULL
9.4 Item changed without refill detection → trust more confident; tiny delta = misprediction
9.5 Non-uniform item surface → accept ±10% error on volume
9.6 Container shape "other" → cylindrical/cuboid only at launch
9.7 First reading → save without consumption_log
9.8 Positive delta → REFILL event, REFILL_DETECTED signal
9.9 Tiny delta (noise) → threshold, ignore < N grams
9.10 Large consumption spike → accept, rate-limit notifications
9.11 Out-of-order readings → compute delta against nearest-earlier timestamp
9.12 Null predicted_item → still compute fill%, notification uses container label

### Hop 10 — Nutrition Service alert emission
10.1 Single-reading threshold crossing may be noise → require 3 consecutive, or <5% single
10.2 Battery fluctuation under LiPo load → EWMA smoothing, 30min hold
10.3 Refill happened but threshold still "low" → REFILL_DETECTED signal resolves
10.4 Producer crash between compute and publish → transactional outbox

### Hop 11 — Nutrition → ThingsBoard attribute update
11.1 TB REST unreachable → retry+DLQ; app falls back to Spring Boot API
11.2 Attribute update race → order by reading_timestamp, drop older
11.3 App backgrounded → pull latest on foreground, don't rely on WS replay

### Hop 12 — Notification Service signal consumption
12.1 Container not in DB → validate, drop+log
12.2 User mismatch (security) → cross-check containers.user_id
12.3 Stale signal (>10min) → drop with STALE_SIGNAL reason
12.4 No devices registered → mark ACTIVE, skip FCM
12.5 All tokens stale → revoke tokens, mark ACTIVE, skip

### Hop 13 — Alert state machine
See section 4 above.
13.1 Concurrent updates → FIFO + @Version optimistic lock
13.2 Refill → consume → refill cycle → cooldown resets on RESOLVED → ACTIVE
13.3 ACKNOWLEDGED alert 24h later → ack expiry job flips to ACTIVE
13.4 Container dormant with ACTIVE alert → auto-expire after 7 days silence
13.5 Item change without refill → resolve with ITEM_CHANGED, re-evaluate
13.6 WARNING signal after CRITICAL active → subsumed, IGNORE
13.7 Escalation > 3 → silent ACTIVE, no more firing

### Hop 14 — FCM delivery
14.1 Thundering herd → per-pod concurrency limit, exponential backoff
14.2 One user multiple devices → iterate all, FCM multicast where possible
14.3 Phone offline → FCM stores and delivers later; sent_at is definitive
14.4 DND mode → CRITICAL uses apns-priority:10 + interruption-level:time-sensitive
14.5 Token rotated between register and send → mark revoked, try next token

### Hop 15 — App receives notification
15.1 App uninstalled → UNREGISTERED → daily cleanup of stale devices
15.2 Deep link on tap → closed enum, whitelist on app side
15.3 Swipe-dismiss → not an acknowledgement
15.4 Read on one device, not other → user-level ack, app open = ack signal

### Hop 16 — App fetches current state after tap
16.1 State changed between notification and open → notification is trigger, not truth
16.2 Ack via app open → POST /alerts/{id}/acknowledge, cooldown starts

### Hop 17 — Depletion Predictor scheduled scan (every 6h)
17.1 Scan-write race → READ COMMITTED acceptable
17.2 Container <3 data points → skip
17.3 Recent dormancy skews prediction → EWMA weighting, require last-7-day activity
17.4 Predictor crash mid-scan → idempotent with computed_at
17.5 Signal burst → pace emission, rely on consumer autoscaling
17.6 Reactive + scheduled overlap → notification dedup handles
17.7 New container without history → UI state "Learning patterns..."

### Hop 18 — Observability
18.1 Correlation ID → readingId → alertId through all hops
18.2 Silent failures → counter per failure type, Grafana alerts
18.3 Cost visibility → AWS resource tags, cost allocation reports

## 6. Known gaps (not yet designed/built)

1. Container offline detection — no producer emits CONTAINER_OFFLINE today
2. Transactional outbox for all SQS publishes (inconsistent across services)
3. FIFO migration: telemetry-events still Standard; alert-signals is FIFO
4. Lid sensor or consecutive-reading logic in firmware
5. Explicit acknowledgement UX path in mobile app
6. Reconciliation between ThingsBoard devices and containers table
7. User-configurable thresholds and quiet hours (phase 2)
8. In-app WebSocket toast for foregrounded-app case
9. Expiry tracking per refill (needs added_at per refill event)
10. End-to-end integration test harness

## 7. Security threat model

| Threat | Control |
|--------|---------|
| Stolen FCM token, impersonation register | JWT required, userId from token only; 5/hr rate limit |
| External direct notification injection | No public send endpoint; only SQS consumer path |
| SQS message injection | Queue policy restricted to producer task role ARNs |
| Signal replay | Stale signal rejection (>10min); idempotency table |
| FCM credential compromise | Secrets Manager; rotate 90 days; scope to Cloud Messaging only |
| Deep link injection | Closed enum, server-constructed, app-side whitelist |
| Notification content leaks on lock screen | Generic copy for sensitive categories (phase 2) |
| Cross-user alert access | Every controller filters by JWT userId |
