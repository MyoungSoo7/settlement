# ADR 0004 — DONE 정산 불변 + Adjustment 로 역정산

**Status:** Accepted
**Date:** 2026-04-23

## Context

환불이 발생하면 정산 금액을 조정해야 한다. 그러나 정산이 이미 `DONE` 상태(= 판매자에게 지급됐다고 간주되는 시점) 라면 원 정산 레코드를 수정하면 감사 추적이 깨진다.

두 가지 설계 선택지:
- **A**: 원 settlement 의 `refunded_amount`/`net_amount` 를 증감.
- **B**: 별도 `settlement_adjustments` 레코드에 음수(-금액) 을 기록.

## Decision

**B 채택.** `SettlementStatus` 에 따라 환불 처리 분기:

| 원 정산 상태 | 환불 처리 |
|-------------|-----------|
| `REQUESTED` / `PROCESSING` | `Settlement.adjustForRefund(amount)` — 원 레코드의 `refunded_amount` 증가, `net_amount` 재계산 |
| `DONE` | `SettlementAdjustment` 음수 레코드 생성 (V4 테이블). 원 정산은 건드리지 않음 |

**이유:**

- DONE 은 원장에서 "정산 완료된 장부 항목" → 회계 관점에서 사후 수정 불가.
- Adjustment 를 별도 행으로 남기면 감사 trail 이 시간순으로 보존된다 (누가 언제 얼마를 역정산했는지).
- 대사 쿼리가 단순해짐 — `Σ(|adjustments|) == Σ(refunds)` 가 T3-⑨(b) inv 2 로 검증.

**DB 방어:**
- V30 트리거가 DONE 정산의 금액 컬럼 UPDATE 를 DB 수준에서 거부.
- `settlement_adjustments.amount CHECK (amount < 0)` — 항상 음수.
- `settlement_adjustments.refund_id UNIQUE` — 환불 1건당 조정 1건.

## Consequences

**Positive**
- DONE 정산은 불변 → 감사 무결성.
- 대사 불변식으로 금액 유실 즉시 탐지 가능.
- 환불 이력 조회가 `refunds` + `settlement_adjustments` JOIN 으로 직관적.

**Negative / Trade-offs**
- 조회 시 `net_amount_effective = settlements.net_amount + SUM(adjustments.amount)` 재계산 필요.
- PROCESSING/DONE 분기 로직이 `AdjustSettlementForRefundService` 에 존재.

## Related

- ADR 0002 — Settlement 상태 머신
- Flyway V4 (settlement_adjustments), V30 (immutability trigger)
- 대사 불변식 #2 (`report` 도메인)
