# Runbook — opslab settlement 테이블 정리(decommission) (ADR 0020 Phase 5.5)

> 목적: settlement DB 물리 분리(Phase 4) 컷오버가 **안정화된 뒤**, opslab 에 남은
> settlement 소유 테이블을 제거해 분리를 최종 완성한다.
> ⚠️ **비가역(DROP)**. 롤백 창이 닫힌 뒤 마지막에 수행한다.

관련: [ADR 0020 Phase 5](../adr/0020-order-settlement-db-split.md#phase-5--하드닝-잔여-작업) ·
[컷오버 런북](settlement-db-cutover.md) ·
스크립트 [`scripts/etl/settlement-opslab-decommission.sh`](../../scripts/etl/settlement-opslab-decommission.sh)

---

## 선결 조건 (모두 충족해야 실행)

- [ ] 컷오버 완료 후 **충분한 안정화 관찰**(권장 2주+) — settlement-service 가 settlement_db 로만 운영.
- [ ] **롤백 창 종료** — 데이터소스 원복(opslab) 시나리오를 더 이상 쓰지 않기로 확정.
- [ ] Phase 5.2 **cross-DB 대사 무드리프트** — `SettlementProjectionDrift*` 알림 없음, 건수 일치.
- [ ] opslab **백업 확보** — `pg_dump` 또는 스토리지 스냅샷(복구 가능 상태).
- [ ] order-service 가 settlement 테이블을 코드로 쓰지 않음(확인됨 — settlement/ledger/payout/
      chargeback/pgreconciliation 클래스가 order 소스에 없음. 스캔 잔재는 Phase 5.5 에서 제거).

## 대상 분류

| 분류 | 테이블 | 처리 |
|---|---|---|
| **DROP** (settlement_db 로 이관 완료) | `settlements`, `settlement_adjustments`, `settlement_index_queue`, `payouts`, `chargebacks`, `ledger_entries`, `ledger_outbox`, `pg_reconciliation_runs`, `pg_reconciliation_discrepancies`, `settlement_loan_deductions`, `settlement_payment_view`, `settlement_order_view`, `settlement_user_view`, `settlement_product_view` | 스크립트로 제거 |
| **KEEP** (order 가 계속 사용) | `outbox_events`, `processed_events`, `audit_logs`, `shedlock`, `batch_run_history` | 유지 |
| **REVIEW** (settlement_db baseline 부재) | `settlement_schedule_config` | 미사용 확인 후 수동 판단 |

---

## 절차

> **스키마**: opslab 의 settlement 잔여 테이블은 `opslab` 스키마에 있고(order Flyway default-schema),
> settlement_db 는 `public` 이다. 스크립트가 각각 `OPSLAB_SCHEMA`(기본 `opslab`)·`SETTLEMENT_SCHEMA`
> (기본 `public`)로 처리하므로 보통 추가 지정 불필요. (과거엔 양쪽을 `public` 으로 가정해 opslab 잔여를
> 전혀 못 지우던 버그가 있었음 — `OpslabDecommissionIT` 로 회귀 검증.)

### 1. 점검 (DRY-RUN, 무해)

```bash
OPSLAB_URL="postgresql://u:p@opslab-host:5432/opslab" \
SETTLEMENT_URL="postgresql://u:p@settlement-db-host:5432/settlement_db" \
./scripts/etl/settlement-opslab-decommission.sh
```
- 각 테이블의 opslab vs settlement_db 행 수를 출력.
- settlement_db 가 opslab 보다 **적으면 ⛔ 차단**(미이관 의심) → 백필/ETL 로 정합화 후 재시도.

### 2. 백업

```bash
pg_dump "$OPSLAB_URL" --format=custom --file=opslab-pre-decommission-$(date +%F).dump
# 또는 관리형 DB 스냅샷
```

### 3. 제거 (CONFIRM=DROP)

```bash
OPSLAB_URL=... SETTLEMENT_URL=... CONFIRM=DROP \
./scripts/etl/settlement-opslab-decommission.sh
```
- 단일 트랜잭션 + `DROP TABLE IF EXISTS ... CASCADE`(자식→부모 순).
- 완료 후 각 테이블 `exists=f` 확인.

### 4. 검증

- order-service 정상(헬스/기능) — settlement 테이블 미사용이므로 영향 없어야 함.
- settlement-service 정상 — settlement_db 로만 동작.
- 모니터링: 신규 에러/DLT 없음.

---

## 롤백

DROP 후 문제가 생기면(이론상 order 영향 없음) **백업에서 해당 테이블만 복원**:
```bash
pg_restore --dbname "$OPSLAB_URL" --table=<table> opslab-pre-decommission-*.dump
```
이 단계는 "롤백 창 종료" 후 수행하므로, 데이터소스 원복 시나리오는 더 이상 지원하지 않는다.

## 잔여 사항 (honest notes)

- order-service 의 settlement 테이블 **생성 마이그레이션은 이력 보존상 남는다**
  (V2/V4/V5/V6/V35/V43~45/V49/V20260616120000~160000). 신규 opslab 부트스트랩 시 빈 테이블이
  재생성되지만 order 코드가 사용하지 않아 무해한 **미사용 잔여**다. 그린필드 설치에선 생략 권장.
  (적용된 Flyway 마이그레이션은 삭제 금지 — 되돌리려면 forward drop 마이그레이션이 필요하나,
  운영 opslab 의 실데이터 보호를 위해 본 정리는 자동 마이그레이션이 아닌 수동 스크립트로 둔다.)
- `settlement_schedule_config` 는 settlement_db baseline 에 없어 미사용 가능성이 높다. 사용처
  확인 후 별도 DROP 또는 settlement_db 로 이관.
