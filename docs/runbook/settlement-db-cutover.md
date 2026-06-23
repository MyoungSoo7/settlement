# Runbook — settlement DB 물리 분리 컷오버 (ADR 0020 Phase 4)

> 목적: `settlement-service` 의 데이터를 공유 `opslab` 에서 전용 `settlement_db` 로
> **무손실·역행가능**하게 이관하고 트래픽을 전환한다.
> 코드 변경은 이미 완료(Phase 1~3 + Phase 4 Chunk 1~3). 이 문서는 **일회성 운영 절차**다.

관련: [ADR 0020](../adr/0020-order-settlement-db-split.md) ·
ETL 스크립트 [`scripts/etl/settlement-db-cutover.sh`](../../scripts/etl/settlement-db-cutover.sh)

---


## 전제 조건

- [ ] `settlement_db` 인스턴스가 프로비저닝됨 (compose: `settlement-db:5432` / k8s: `settlement-db-service`).
- [ ] settlement-service 자체 Flyway(V1 baseline)가 `settlement_db` 에 스키마를 생성 완료
      (`SettlementDbBootIT` 가 검증하는 그 스키마). **ETL 은 `--data-only`** 이므로 스키마 선행 필수.
- [ ] order-service / settlement-service 가 같은 Kafka(redpanda)·같은 JWT 시크릿을 공유.
- [ ] `pg_dump` / `psql` 가 양쪽 DB 에 네트워크로 도달 가능한 운영 호스트.

## 데이터 분류 (무엇을 어떻게 옮기나)

| 분류 | 테이블 | 방법 |
|---|---|---|
| **원천 집계** (복사) | `settlements`, `settlement_adjustments`, `payouts`, `chargebacks`, `ledger_entries`, `ledger_outbox`, `pg_reconciliation_runs`, `pg_reconciliation_discrepancies`, `settlement_loan_deductions`, `audit_logs`, `processed_events` | ETL 스크립트 `--data-only` 복사 |
| **읽기모델** (재구축) | `settlement_payment_view`, `settlement_order_view`, `settlement_user_view`, `settlement_product_view` | 백필 엔드포인트로 이벤트 재발행 → 재구축 |
| **드레인 후 제외** | `outbox_events` | 컷오버 전 전량 발행(드레인). settlement 는 빈 outbox 로 시작 |
| **전이성 제외** | `settlement_index_queue` | ES 재색인으로 재생성 |

> 왜 읽기모델을 복사하지 않나: `*_view` 는 order 이벤트로 채워지는 **파생** 데이터다.
> 백필(Chunk 3)이 기존 users/products/orders/captured-payments 를 이벤트로 재발행하면
> settlement 컨슈머가 `(consumer_group,event_id)` 멱등 + upsert 로 다시 채운다.
> 정산 *생성* 경로는 `settlements.payment_id UNIQUE` 가 막으므로 중복 정산은 생기지 않는다.

---

## 절차

### 0. 사전 점검 (무중단)

```bash
SRC_URL="postgresql://USER:PASS@opslab-host:5432/opslab" \
DST_URL="postgresql://USER:PASS@settlement-db-host:5432/settlement_db" \
DRY_RUN=1 ./scripts/etl/settlement-db-cutover.sh
```
원천 행 수를 기록해 둔다(이관 후 대조용).

### 1. settlement 쓰기 정지 (freeze)

- settlement-service 의 **배치/스케줄러 정지**: 정산 생성·payout·대사 잡을 멈춘다
  (`spring.batch.job.enabled=false` 또는 스케일 0). 읽기는 계속 허용 가능.
- order-service 는 계속 동작 — 발생하는 이벤트는 Kafka/Outbox 에 쌓여 컷오버 후 소비된다.

### 2. outbox 드레인

- settlement-origin `outbox_events` / `ledger_outbox` 의 미발행 행을 **0** 으로 만든다
  (OutboxPublisherScheduler 가 다 내보낼 때까지 대기). 미발행 잔량 확인:
  ```bash
  psql "$SRC_URL" -tAc "SELECT count(*) FROM outbox_events WHERE published_at IS NULL;"
  ```

### 3. 데이터 이관 (ETL)

```bash
SRC_URL="postgresql://USER:PASS@opslab-host:5432/opslab" \
DST_URL="postgresql://USER:PASS@settlement-db-host:5432/settlement_db" \
./scripts/etl/settlement-db-cutover.sh
```
- 단일 `pg_dump | psql --single-transaction` → 중간 실패 시 전체 롤백(부분 적재 없음).
- 스크립트가 대상 `settlements` 비어있음을 선검사(중복 적재 차단).

### 4. 트래픽 컷오버

- settlement-service 데이터소스를 `settlement_db` 로 가리키도록 배포
  (compose: 이미 `settlement-db:5432`; k8s: `settlement-config` 의 `SPRING_DATASOURCE_URL`).
- settlement-service 기동 → `ddl-auto=validate` 가 스키마 정합성 확인.

### 5. 읽기모델 재구축 (백필)

```bash
curl -X POST https://<gateway>/admin/settlement-projection/backfill \
     -H "Authorization: Bearer <ADMIN_JWT>"
# → {"users":N,"products":N,"orders":N,"payments":N}
```
- 멱등하므로 재실행 안전. lag 가 흡수될 때까지 대기.

### 6. 검증 (대사)

- `settlement_db` 행 수 vs 0단계 기록 대조.
- 대사 잡 1회 수동 실행 → ledger 3불변식·PG 대사 무결성 확인(ADR 0007).
- `*_view` 카운트가 order 원천과 일치하는지 표본 점검.

### 7. 배치/스케줄러 재개

- 2단계에서 멈춘 잡 재가동. 컷오버 완료.

---

## 롤백

| 시점 | 롤백 |
|---|---|
| 4단계 이전 | ETL 결과 폐기(settlement_db TRUNCATE). 영향 없음 — opslab 가 여전히 원천 |
| 4단계 이후 문제 | settlement 데이터소스를 **opslab 로 원복** 배포. ADR 0020 Phase 4 "데이터소스 원복" |
| 백필 오류 | `*_view` TRUNCATE 후 5단계 재실행(멱등) |

opslab 원본은 컷오버 직후 **즉시 삭제하지 않는다** — 안정화(ADR Phase 5) 확인 후 정리.

---

## 재실행(idempotency) 주의

- ETL 스크립트는 `settlements` 비어있음을 강제한다. 재이관하려면 대상 집계 테이블을
  먼저 `TRUNCATE ... CASCADE` 하거나 클린 `settlement_db` 로 시작할 것.
- 백필은 항상 멱등(새 event_id 발행 + 컨슈머 dedup + view upsert).
