# notification-service

Central notification hub for the settlement MSA. Consumes domain events from
Kafka and **fans them out to multiple channels (log / Slack / email)
concurrently using Kotlin coroutines**, with per-channel retry, backoff, and
timeout, plus event-id idempotency.

Kotlin-first, Spring Boot 3.3.x, hexagonal (domain / application / adapter).
**Port 8130.**

## Why coroutines

The dispatcher is the point of the service. It sends one `Notification` to every
enabled channel **at the same time**:

```kotlin
coroutineScope { channels.map { async { it.send(n) } }.awaitAll() }
```

Each channel is wrapped in `withTimeout(...)` + bounded retry/backoff, so a slow
or failing channel is isolated — it neither blocks nor fails the others. Results
are collected per channel (`SUCCESS` / `FAILURE` with attempt count).

## Architecture

```
adapter.in.web     NotificationController   POST /notifications/send, GET /notifications/demo
adapter.in.kafka   DomainEventListener      @KafkaListener (gated by app.kafka.enabled)
application         NotificationDispatcher   coroutine fan-out + retry/timeout
                   NotificationChannel      outbound port (suspend fun send)
                   DedupeStore              idempotency port (in-memory TTL impl)
adapter.out.channel LogChannel / SlackChannel / EmailChannel
domain             Notification, NotificationType, NotificationTemplate  (pure)
```

- **LogChannel** — always enabled, zero external deps → always demoable.
- **SlackChannel** — enabled only when `SLACK_WEBHOOK_URL` is set.
- **EmailChannel** — enabled only when `MAIL_USERNAME` + `MAIL_PASSWORD` are set.

## Run

```bash
./gradlew bootRun
# service listens on :8130, boots healthy with NO Kafka broker

curl -s localhost:8130/actuator/health          # {"status":"UP",...}
curl -s localhost:8130/notifications/demo        # per-channel results
curl -s -X POST localhost:8130/notifications/send \
  -H 'Content-Type: application/json' \
  -d '{"type":"PAYMENT_CONFIRMED","recipient":"user@lemuel.co.kr","subject":"결제 완료","body":"결제가 확인되었습니다.","eventId":"evt-1"}'
```

Sample `/notifications/demo` response (log-only, no Slack/mail configured):

```json
{"deduped":false,"allSucceeded":true,"results":[{"channel":"log","status":"SUCCESS","attempts":1,"error":null}]}
```

## Build & test

```bash
./gradlew build      # compiles + runs all tests
./gradlew test       # tests only
```

## Docker

```bash
docker build -t notification-service .
docker run -p 8130:8130 notification-service
```

Multi-stage (temurin 21-jdk build → 21-jre runtime), non-root, `EXPOSE 8130`.

## Kafka

Inbound `@KafkaListener` consumes:

- `lemuel.settlement.confirmed`
- `lemuel.payment.confirmed`
- `lemuel.investment.executed`

Each event is JSON-decoded, mapped to a `Notification` via `NotificationTemplate`,
deduped by `eventId` (or Kafka key), and dispatched. **The listener is gated by
`app.kafka.enabled` (default `false`)** and the Kafka health indicator is
disabled, so the app boots and serves REST even with no broker reachable
(essential for tests/containers). Enable with `APP_KAFKA_ENABLED=true`.

## Environment

| Var | Default | Purpose |
|-----|---------|---------|
| `SERVER_PORT` | `8130` | HTTP port |
| `APP_KAFKA_ENABLED` | `false` | Turn the Kafka listener on |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker(s) |
| `KAFKA_GROUP_ID` | `notification-service` | Consumer group |
| `SLACK_WEBHOOK_URL` | _(unset)_ | Enables Slack channel |
| `MAIL_HOST` / `MAIL_PORT` | `smtp.gmail.com` / `587` | SMTP server |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | _(unset)_ | Enables email channel |
| `MAIL_FROM` | `no-reply@lemuel.co.kr` | From address |
| `APP_DEDUPE_TTL_MINUTES` | `30` | Idempotency window |
| `APP_DISPATCH_TIMEOUT_MS` | `3000` | Per-channel timeout |
| `APP_DISPATCH_MAX_ATTEMPTS` | `3` | Retry attempts per channel |
| `APP_DISPATCH_BACKOFF_MS` | `50` | Base backoff (exp: 50/100/200…) |

## TODOs (beyond MVP)

- Real recipient preferences / routing from a DB (per-user channel opt-in).
- Per-user routing rules (e.g. large settlements → Slack + email; small → log).
- A proper template engine (Thymeleaf/Freemarker) + i18n for message bodies.
- Durable, shared idempotency store (Redis/DB) — current one is in-memory,
  single-instance, non-durable across restarts.
- DLQ + retry topic for poison Kafka messages.
- Metrics per channel (success/failure/latency) exported to Prometheus.
