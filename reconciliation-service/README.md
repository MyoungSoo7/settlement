# reconciliation-service

정산 대사 (settlement reconciliation) microservice — Kotlin-first, Spring Boot.

Reconciles settlement records (the **expected** view, from settlement) against
payment / payout / ledger records (the **actual** view, from PG / bank), keyed by
a business key (e.g. `paymentKey` / `orderId`), and reports discrepancies. The
service fetches multiple sources **concurrently with Kotlin coroutines**, then
diffs them with a pure, unit-tested engine.

Standalone Gradle build — **not** part of settlement's multi-module build. Port **8131**.

## Architecture (hexagonal)

```
domain/       ReconRecord, Discrepancy (sealed), ReconciliationReport,
              ReconciliationEngine   <- pure, no Spring, fully testable
application/  ReconciliationSource (port, suspend fetch), ReconciliationService
              (coroutine orchestration: concurrent fetch + diff)
adapter/
  in/web/        ReconciliationController (POST /run, GET /demo) + DTOs
  in/schedule/   ReconciliationScheduler (@Scheduled, fail-open)
  out/source/    SampleExpected/ActualSource (bundled, zero-dep demo),
                 Settlement/PaymentHttpSource (skeletons -> real services)
```

**Recon engine.** `ReconciliationEngine.reconcile(expected, actual)` keys both
sides by `businessKey`, then classifies each key: matched (omitted from the
report), `MISSING` (in expected, not actual), `EXTRA` (in actual, not expected),
`AMOUNT_MISMATCH` (both present, `|Δ| > toleranceKrw`), `STATUS_MISMATCH` (both
present, amounts agree, status differs). Amount check precedes status. Tolerance
is configurable (default 1 KRW).

**Concurrent fetch.** `ReconciliationService.reconcileFromSources` opens one
`coroutineScope`, launches every source's `suspend fetch` as an `async`, awaits
them all, then diffs — so N sources are pulled in parallel, not serially. Proven
in tests via `runTest`'s virtual clock (two 500 ms sources complete in 500 ms).

## Run

Requires **JDK 21** (the build pins `jvmToolchain(21)`, Spring Boot 3.3.x,
Kotlin 2.0.x, Gradle 8.10.2 — deliberately avoiding the JDK-25 / Kotlin-2.3
toolchain landmine).

```bash
./gradlew bootRun
```

```bash
# health
curl -s localhost:8131/actuator/health          # {"status":"UP"}

# bundled demo — runs sample sources end-to-end, returns every discrepancy type
curl -s localhost:8131/reconciliation/demo | jq

# reconcile caller-supplied sets
curl -s -X POST localhost:8131/reconciliation/run \
  -H 'Content-Type: application/json' \
  -d '{
        "expected":[{"businessKey":"k1","amountKrw":1000,"status":"PAID"}],
        "actual":[{"businessKey":"k1","amountKrw":2000,"status":"PAID"}],
        "toleranceKrw":1
      }' | jq
```

## Endpoints

| Method | Path                     | Purpose                                             |
|--------|--------------------------|-----------------------------------------------------|
| GET    | `/actuator/health`       | Liveness — `{"status":"UP"}`                         |
| POST   | `/reconciliation/run`    | Reconcile body `{expected:[], actual:[], toleranceKrw?}` |
| GET    | `/reconciliation/demo`   | Run bundled sample sources; report has all discrepancy types |

## Config / env

| Env                              | Default                       | Meaning                              |
|----------------------------------|-------------------------------|--------------------------------------|
| `SERVER_PORT`                    | `8131`                        | HTTP port                            |
| `APP_RECONCILIATION_CRON`        | `0 0 19 * * *` (Asia/Seoul)   | Scheduled recon cron (daily post-close) |
| `APP_RECONCILIATION_TOLERANCE_KRW` | `1`                         | Amount tolerance (KRW)               |
| `SETTLEMENT_BASE_URL`            | `http://settlement-service:8082` | Skeleton expected HTTP source     |
| `PAYMENT_BASE_URL`               | `http://order-service:8080`   | Skeleton actual HTTP source          |

## Scheduler

`@Scheduled(cron=…, zone=Asia/Seoul)` reconciles the prior settlement day over
the configured sources and logs a summary. **Fail-open**: any source error is
caught and logged, never crashing the app.

## Build & test

```bash
./gradlew build      # compiles + runs all tests
./gradlew test
```

## Docker

```bash
docker build -t reconciliation-service .
docker run -p 8131:8131 reconciliation-service
```

Multi-stage (`21-jdk` build → `21-jre` runtime), non-root user, `EXPOSE 8131`.

## TODOs

- Real source adapters: implement `SettlementHttpSource` / `PaymentHttpSource`
  against the settlement & payment/PG APIs (or straight off DB / ledger), map
  rows to `ReconRecord`. Add a bank-payout source as a 3rd concurrent source.
- Persist discrepancies (DB table) + history; expose a query API.
- Alerting: emit discrepancies to **notification-service** (the sibling service)
  when a scheduled run is not clean.
- Stream large reconciliations via `Flow` instead of materializing full lists,
  for month-end volumes.
