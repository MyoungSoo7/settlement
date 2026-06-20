# ADR 0002 — Settlement 상태 머신

**Status:** Accepted
**Date:** 2026-04-23

## Context

정산은 실 화폐가 오가는 원장이므로 **상태 전이가 엄격**해야 한다. 초기 개발 단계에선 레거시 상태값(`PENDING`, `CONFIRMED` 등)이 혼재되어 있었고, 상태 전이 로직이 여러 곳에 흩어져 있었다.

## Decision

`SettlementStatus` enum 을 5개로 고정:

```
REQUESTED → PROCESSING → DONE
                       ↘ FAILED → REQUESTED (재시도)

REQUESTED → CANCELED (환불로 net=0 일 때)
```

**불변식:**

1. **`DONE` 은 종료 상태 (terminal)** — 이후 금액 변경 금지.
   - 도메인: `Settlement.adjustForRefund()` 에서 DONE 이면 `IllegalStateException`.
   - DB: V30 트리거가 `payment_amount`/`commission`/`net_amount` UPDATE 를 거부.
2. **`CANCELED` 도 종료 상태** — 재활성 불가.
3. 상태 전이는 `Settlement` 도메인 메서드(`startProcessing()`, `complete()`, `fail(reason)`, `retry()`, `cancel()`)를 통해서만. DB/어댑터에서 직접 status 컬럼 UPDATE 금지.
4. 레거시 문자열(`PENDING`, `CONFIRMED`)은 V26 마이그레이션으로 모두 정규화. `SettlementStatus.fromString()` 은 혹시 남아있는 롤백 데이터에 대비해 `REQUESTED` fallback.

## Consequences

**Positive**
- 상태 전이 위반 시 즉시 예외 → 단위 테스트에서 잡힘.
- DB 트리거로 원장 불변성을 애플리케이션 버그에서도 방어 (이중 안전망).
- 역정산은 **별도 `SettlementAdjustment` 레코드**로 기록해 원 DONE 정산은 건드리지 않음 → 감사 추적 보존.

**Negative / Trade-offs**
- 환불 처리가 복잡해짐 (adjustments 별도 관리).
- 트리거 작성/테스트 오버헤드.

## Related

- ADR 0004 — Reverse Settlement via Adjustment
- Flyway V26 (status 정규화), V30 (immutability trigger)
- 도메인 테스트: `SettlementFullTest`, `SettlementInvariantTest`
