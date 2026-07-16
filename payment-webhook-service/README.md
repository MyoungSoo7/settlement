# payment-webhook-service (Go)

Fast, idempotent ingestion of **Toss payment webhooks** for the `settlement` MSA.
It verifies the webhook HMAC signature, deduplicates by payment key, and publishes
a normalized domain event to Kafka.

- **Language / module:** Go 1.22 · `github.com/myoungsoo7/settlement/payment-webhook-service`
- **Port:** `8111` (env `PAYMENT_WEBHOOK_PORT`)
- **Kafka topic:** `lemuel.payment.confirmed` (repo convention `lemuel.<domain>.<event>`)

## Endpoints

| Method | Path             | Description |
|--------|------------------|-------------|
| GET    | `/healthz`       | Liveness. `200 {"status":"UP"}` |
| POST   | `/webhooks/toss` | Ingest a Toss payment webhook. `200 {"received":true,"duplicate":false}` |

### `POST /webhooks/toss` flow
1. **Capture raw body** before JSON decode (HMAC is over the exact bytes).
2. **Verify HMAC-SHA256** of the raw body under `TOSS_WEBHOOK_SECRET`, base64-encoded,
   compared against the `Toss-Signature` header using **constant-time** `hmac.Equal`.
   Mismatch → `401`.
3. **Idempotency**: dedupe by `eventType:paymentKey` via an in-memory TTL store.
   A duplicate returns `200 {"received":true,"duplicate":true}` and is **not** re-published.
4. **Publish** a normalized `PaymentConfirmedEvent` to `lemuel.payment.confirmed`.

Expected request body (Toss-style):
```json
{
  "eventType": "PAYMENT_STATUS_CHANGED",
  "data": {
    "paymentKey": "pk_abc",
    "orderId": "order_1",
    "status": "DONE",
    "totalAmount": 15000
  }
}
```

## Environment variables

| Var                     | Default | Notes |
|-------------------------|---------|-------|
| `PAYMENT_WEBHOOK_PORT`  | `8111`  | HTTP listen port. |
| `TOSS_WEBHOOK_SECRET`   | *(empty)* | HMAC secret. Empty ⇒ **all** signatures rejected. |
| `KAFKA_BROKERS`         | *(unset)* | Comma-separated brokers. Unset ⇒ **LogPublisher** (no broker needed); set ⇒ **KafkaPublisher** (segmentio/kafka-go). |

## Run

```bash
# No broker: uses the LogPublisher, so it runs standalone.
export TOSS_WEBHOOK_SECRET=s3cr3t
go run ./cmd/server

# With Kafka:
export KAFKA_BROKERS=localhost:9092
go run ./cmd/server
```

Docker:
```bash
docker build -t payment-webhook-service .
docker run --rm -p 8111:8111 -e TOSS_WEBHOOK_SECRET=s3cr3t payment-webhook-service
```

## curl example (computes a valid HMAC signature)

```bash
SECRET=s3cr3t
BODY='{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"pk_abc","orderId":"order_1","status":"DONE","totalAmount":15000}}'

# base64(HMAC-SHA256(rawBody, secret)) — must be over the EXACT bytes sent.
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -sS -X POST http://localhost:8111/webhooks/toss \
  -H "Content-Type: application/json" \
  -H "Toss-Signature: $SIG" \
  --data "$BODY"
# => {"received":true,"duplicate":false}

# Replay the SAME request → deduped, not re-published:
curl -sS -X POST http://localhost:8111/webhooks/toss \
  -H "Toss-Signature: $SIG" --data "$BODY"
# => {"received":true,"duplicate":true}
```

Health check:
```bash
curl -s http://localhost:8111/healthz   # {"status":"UP"}
```

## Tests

```bash
go test ./...
```
Covers: signature verify (valid passes; tampered body / tampered signature fail),
idempotency (same key twice → second flagged duplicate, publisher called once, TTL expiry),
and the handler happy-path (valid signed request → 200 + publisher invoked) via `httptest`
with a fake publisher and the in-memory store.

## Layout

```
cmd/server/            main: config, publisher selection, graceful shutdown
internal/webhook/      Toss types, HMAC signature verify, HTTP handler
internal/idempotency/  IdempotencyStore interface + in-memory TTL impl
internal/publisher/    EventPublisher interface, LogPublisher, KafkaPublisher, event contract
internal/httpserver/   router (/healthz + /webhooks/toss)
```

## TODO (beyond MVP)

- **Real Toss signature spec.** The current scheme is a generic
  base64(HMAC-SHA256(rawBody, secret)). Replace `internal/webhook/signature.go`
  with Toss' actual webhook signature verification (header format, signed
  payload construction, key/version handling) before production use. The raw-body
  capture and constant-time compare stay as-is.
- **Durable idempotency store.** `MemoryStore` is per-instance and lost on restart.
  Back `IdempotencyStore` with Redis (SET NX + TTL) or a DB unique constraint so
  dedupe survives restarts and works across replicas.
- **Delivery guarantees.** Publish currently happens inline; consider an outbox /
  retry path (mirroring the settlement outbox pattern) for at-least-once delivery
  under broker outages.
- **Observability.** Add Prometheus metrics (accepted / duplicate / rejected /
  publish-failed) and request tracing.
