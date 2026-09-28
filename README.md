# StorNuAge Notification Service

Push notifications for low-stock and low-battery alerts across the StorNuAge
fleet. Event-driven (not polled), FIFO-ordered per container, with full
dedup, escalation, and cross-platform delivery via FCM.

## Quick links

- **Architecture & edge cases:** [`docs/notification-service-architecture.md`](docs/notification-service-architecture.md)
- **Audit checklist:** [`docs/notification-service-audit-checklist.md`](docs/notification-service-audit-checklist.md)

## What this service does

```
alert-signals SQS (FIFO) ──► AlertSignalConsumer
                                    │
                                    ▼
                             SignalProcessor
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
        IdempotencyGate     AlertStateMachine    NotificationDelivery
          (processed_          (alert_state           (FCM → APNs/
           signals)             audit log)            Android)
```

Producers (Nutrition Service, Depletion Predictor) publish signals with
`MessageGroupId = containerId`. Signals are deduped, evaluated against
the alert state machine, and deliverable decisions trigger FCM push.

## Running locally

```bash
# Prerequisites
#  - Java 21
#  - Docker (for Postgres + LocalStack)
#  - A Firebase project with APNs key uploaded
#  - Service account JSON downloaded to ./local-fcm-credentials.json

# Start dependencies
docker-compose up -d postgres localstack

# Create local queues
aws --endpoint-url=http://localhost:4566 sqs create-queue \
    --queue-name stornuage-alert-signals-dlq
aws --endpoint-url=http://localhost:4566 sqs create-queue \
    --queue-name stornuage-alert-signals.fifo \
    --attributes FifoQueue=true,DeduplicationScope=messageGroup

# Run
export NOTIFICATION_FIREBASE_CREDENTIALS_PATH=./local-fcm-credentials.json
export DB_URL=jdbc:postgresql://localhost:5432/stornuage
export DB_USER=postgres
export DB_PASSWORD=postgres
export TB_JWT_SIGNING_KEY=<dev-signing-key>

mvn spring-boot:run
```

## Project layout

```
src/main/java/com/stornuage/notification/
├── NotificationServiceApplication.java    entry point
├── config/                                 Firebase, SQS, properties, MVC
├── auth/                                   JWT filter + resolver
├── consumer/                               @SqsListener (FIFO)
├── service/
│   ├── SignalProcessor.java                orchestrator
│   ├── IdempotencyGate.java                separate bean — @Transactional works
│   ├── AlertStateMachine.java              the brain
│   ├── NotificationComposer.java           human-readable copy
│   ├── NotificationDeliveryService.java    FCM fan-out + delivery log
│   ├── DeviceRegistrationService.java      upsert, revoke
│   ├── UserRateLimiter.java                Resilience4j, per-user
│   └── MaintenanceJobs.java                scheduled tasks
├── controller/                             REST + exception handler
├── client/FcmClient.java                   FCM with retry + revoke logic
├── domain/                                 JPA entities + enums
├── dto/                                    request/response/signal payloads
├── exception/                              domain exceptions
└── repository/                             Spring Data JPA repos

src/main/resources/
├── application.yml
└── db/migration/V1__notification_schema.sql
```

## Key design decisions

1. **FIFO SQS with `MessageGroupId = containerId`** — serializes signals
   per container, eliminating race conditions in the state machine.
2. **@Version optimistic locking** — belt-and-suspenders with FIFO.
3. **Idempotency in a separate bean** — Spring proxy-based @Transactional
   does not intercept self-invocation.
4. **REQUIRES_NEW for idempotency and delivery** — marker and delivery
   outcomes persist even if peer transactions fail.
5. **Secrets via Secrets Manager only** — Firebase credentials, DB
   credentials, TB JWT signing key all pulled at runtime.
6. **Rate-limit registration endpoint (5/hr)** — primary control against
   token-registration abuse.
7. **userId always from JWT** — never trust the request body for identity.

## Contributing

Before opening a PR, check against
[`docs/notification-service-audit-checklist.md`](docs/notification-service-audit-checklist.md).
