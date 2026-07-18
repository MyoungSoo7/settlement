# Seed — settlement-service pgreconciliation(PG 대사) as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`settlement-service-pgreconciliation.seed.yaml`](./settlement-service-pgreconciliation.seed.yaml)
> 부모: [회계 코어 루프](./settlement-service-accounting-core.seed.md) · 자매: [chargeback](./settlement-service-chargeback.seed.md)

## Goal (한 줄)

**settlement-service pgreconciliation 서브도메인(PG 정산파일 vs 내부 결제 원장 대사 · 차이 자동 분류 ·
운영자 결정 · clawback 역정산)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해,
회귀 기준선 · 면접 문서 · 후속 확장의 베이스로 사용한다.**

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **매칭 알고리즘** (`PgReconciliationMatcher` — 프레임워크 의존 0 순수 도메인):
   키 = `pg_transaction_id`. 분류 6분기 —
   `DUPLICATE`(PG 내 키 중복, 이중 청구 의심) · `MISSING_INTERNAL`(PG 에만 — 매출 누락 의심, 최고 위험) ·
   `MISSING_PG`(내부에만) · matched(diff=0) · `ROUNDING_DIFF`(diff < **1.00원**) · `AMOUNT_MISMATCH`(diff ≥ 1원)
2. **상태머신** — `PENDING → APPROVED | REJECTED` (둘 다 종단, 재결정 불가). `ROUNDING_DIFF` 는 생성 시
   `AUTO_CORRECTED` 직행(resolvedBy=SYSTEM). REJECT 는 사유 필수 (`ReconciliationDiscrepancy.java:47-51,100-121`)
3. **승인 → clawback 파이프라인** — approve 는 같은 tx 에서 Outbox 적재 → 폴러가
   `lemuel.pgreconciliation.discrepancy_approved` 발행(**서비스 내부 self-consume 토픽**) → 컨슈머가
   **회수 방향만** 조정: `AMOUNT_MISMATCH`(pg<internal)=|차액|, `MISSING_PG`=내부금액 전액,
   그 외는 조용히 버리지 않고 `adjustments.skipped{reason}` 메트릭 + WARN
4. **clawback 적용 순서** (`ApplyReconciliationAdjustmentService.java:65-106`) — 재전송 멱등 스킵 →
   **holdback 우선 흡수 → net 축소** → 음수 감사 row(`ofReconciliation`) + discrepancyId 1:1 UNIQUE.
   **DONE 정산은 직접 적용 불가** — 감사 레코드만 + `settlement_done_manual_clawback` 메트릭 + 수기 회수 이관.
   기준일은 KST 고정(타임존 비의존)
5. **API** — `/admin/pg-reconciliation`: 파일 업로드(multipart CSV)·runs 조회 2·approve·reject

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 매칭 분류 6분기 일치 | `PgReconciliationMatcherTest` |
| AC-2 | 상태머신·AUTO_CORRECTED 직행·사유 필수 | `ReconciliationDiscrepancyTest`·`ReconciliationRunTest` |
| AC-3 | Outbox 발행·clawback 부호 규칙·DONE 수기 이관 | `ResolveDiscrepancyServiceTest` 외 |
| AC-4 | CSV 파싱·API 표면 일치 | `CsvPgFileParserAdapterTest`·`PgReconciliationControllerTest` |
| AC-5 | LINE ≥ 90% | `:settlement-service:jacocoTestCoverageVerification` |
| AC-6 | 도메인 OO 불변식 | `guard.mjs` OO-* + `oo-gate.test.mjs` |

## Known Issues (발견만 기록)

- **KI-1 (✅ 2026-07-19 해소)**: 파일 재업로드 이중 clawback 갭 → 파일 내용 **SHA-256 멱등** 도입:
  서비스가 기존 COMPLETED run 을 멱등 반환(`pg.reconciliation.duplicate_file.hit`) + DB 부분 UNIQUE
  (`uq_pg_recon_runs_file_sha256_completed`, FAILED 는 재시도 허용).
- **KI-2 (by-design)**: 과소 정산 방향(셀러에게 더 지급)은 자동 보정 없음 — clawback 방향만, 명시적 skip.
- **KI-3 (by-design)**: 내부 토픽 `discrepancy_approved` 는 JSON Schema 계약 없음 (ADR 0024 는 cross-service 만).
