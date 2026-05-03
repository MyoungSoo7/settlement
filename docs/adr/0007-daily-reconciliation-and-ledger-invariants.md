# ADR 0007 — 일일 대사 + 기간 대사 3 불변식

**Status:** Accepted
**Date:** 2026-04-23

## Context

정산 시스템은 **금액 유실을 즉시 탐지** 해야 한다. 결제→환불→정산→조정→이벤트로 이어지는 복잡한 파이프라인에서 한 단계라도 실패·누락되면 장부가 무너진다. 단일 트랜잭션 테스트로는 커버되지 않는 **전체 시스템 수준 불변식**을 주기적으로 검증해야 한다.

## Decision

두 종류의 대사 서비스를 공존시킨다.

### (A) 일일 대사 — `ReconcileDailyTotalsService`

- 매일 03:05 배치로 전일자 검증.
- 1 불변식: `Σ(payments) - Σ(refunds) = Σ(net) + Σ(commission)`.
- 결과는 로그 (ERROR) 와 `reconciliation_report` 도메인 레코드.

### (B) 기간 대사 — T3-⑨(b) `report` 도메인

관리자/재무 팀이 호출하는 `/api/reports/cashflow` 응답에 `reconciliation` 섹션 포함.

**3 불변식:**

| # | 이름 | 의미 |
|---|------|------|
| 1 | `payments_minus_refunds_equals_settlement` | 결제-환불 = 정산 net + commission (A 와 동일, 기간 단위) |
| 2 | `adjustments_equal_linked_refunds` | \|Σ(settlement_adjustments)\| = Σ(refunds.COMPLETED linked) — 역정산 원장 정합성 |
| 3 | `outbox_published_equals_settlements_created` | PaymentCaptured PUBLISHED 이벤트 수 == 정산 생성 수 — 이벤트 파이프라인 원자성 |

**메트릭:**
- `cashflow_report_generation_duration_seconds` (Timer)
- `cashflow_reconciliation_mismatch_total{check="..."}` (Counter) — check 별 태깅

**알림 (Alertmanager):**
- `CashflowReconciliationMismatch` critical — `increase(..mismatch_total[1h]) > 0`
- `CashflowReconciliationPersistentFailure` critical — `increase(..[6h]) > 10`

## Consequences

**Positive**
- 금액 유실이 운영팀에 정량적으로 노출 (금액·건수·check 명).
- 재무팀이 월말 마감 전 금액 검증 가능.
- 이벤트 기반 전환(ADR 0005)의 신뢰도를 실시간으로 증명 (inv 3).

**Negative / Trade-offs**
- 3개 불변식별 추가 JDBC 쿼리 → 리포트 지연 가능성 (Timer 로 p95 감시).
- inv 3 은 기간 경계 시각에서 오차 가능 (경계 타이밍 문서화).

## Related

- ADR 0003 — Outbox (inv 3 의 전제)
- ADR 0004 — Reverse Settlement (inv 2 의 전제)
- `report/application/service/GenerateCashflowReportService.java`
- `monitoring/alert-rules.yml` `lemuel_cashflow_report_alerts` 그룹
