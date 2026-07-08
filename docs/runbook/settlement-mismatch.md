# Runbook — 일일 대사 불일치

**연결 로그:** `[Reconciliation] {date} MISMATCH discrepancy=...` (ERROR)
**배치:** `ReconcileDailyTotalsService.reconcile(targetDate)` — 매일 03:05

## 증상

- ERROR 로그에 `discrepancy != 0`.
- `/admin/reconciliation?date=YYYY-MM-DD` 가 `matched=false`.
- 보다 광범위한 기간 대사는 `/api/reports/cashflow` 참조 (T3-⑨(b) 엔드포인트).

## 불변식

```
결제합계 − 환불합계 = 정산 net 합계 + 정산 commission 합계
```

위반하면 금액이 파이프라인 어딘가에서 샜다는 의미.

## 1. 1차 조사 — 각 단계 합계 비교

```sql
-- 결제 합계 (CAPTURED 기준)
SELECT COALESCE(SUM(amount), 0) AS captured
FROM opslab.payments
WHERE status='CAPTURED' AND captured_at::date = '2026-04-23';

-- 환불 합계
SELECT COALESCE(SUM(amount), 0) AS refunded
FROM opslab.refunds
WHERE status='COMPLETED' AND completed_at::date = '2026-04-23';

-- 정산 net + commission
SELECT
  COALESCE(SUM(net_amount), 0) AS net,
  COALESCE(SUM(commission), 0) AS commission,
  COALESCE(SUM(net_amount + commission), 0) AS actual
FROM opslab.settlements
WHERE settlement_date = '2026-04-23'
  AND status <> 'CANCELED';
```

`captured - refunded` 와 `actual` 를 비교. 어느 쪽이 크냐에 따라:

### `actual` 가 작다 → 정산 생성 누락

- 문제 결제 찾기:
  ```sql
  SELECT p.id, p.amount
  FROM opslab.payments p
  LEFT JOIN opslab.settlements s ON s.payment_id = p.id
  WHERE p.status='CAPTURED'
    AND p.captured_at::date = '2026-04-23'
    AND s.id IS NULL;
  ```
- Kafka 컨슈머 로그 확인 → poison pill 여부.
- 복구: 누락된 paymentId 에 대해 `CreateSettlementFromPaymentService` 수동 호출.

### `actual` 가 크다 → 정산 중복 또는 환불 누락 반영

- 중복 정산 확인:
  ```sql
  SELECT payment_id, count(*)
  FROM opslab.settlements
  WHERE settlement_date = '2026-04-23'
  GROUP BY payment_id HAVING count(*) > 1;
  ```
- 환불 COMPLETED 인데 adjustment 가 없는 건:
  ```sql
  SELECT r.id, r.amount, r.completed_at
  FROM opslab.refunds r
  LEFT JOIN opslab.settlement_adjustments sa ON sa.refund_id = r.id
  WHERE r.status='COMPLETED'
    AND r.completed_at::date = '2026-04-23'
    AND sa.id IS NULL;
  ```

## 2. 복구

- 누락 정산: 수동 생성 API 또는 payment_id 지정 재실행.
- 중복 정산: 관리자 도구로 취소 (DONE 이 아니라면).
- 누락 adjustment: `AdjustSettlementForRefundService` 수동 호출.

복구 후 `ReconcileDailyTotalsService.reconcile(targetDate)` 재실행해 `matched=true` 확인.

## 3. 에스컬레이션

- 금액 차 > 100만 원 → 재무팀.
- 같은 날짜가 24h 내 복구 안 됨 → 장애 공지.

## 4. 사후

- 원인별 분류: 애플리케이션 버그 vs Kafka 유실 vs DB 직접수정 (누가 언제).
- 재발 방지: `audit_logs` 조회(추후 T2-⑤ 완료 후) 로 수동 DB 작업 추적.

## 관련 런북

- `cashflow-reconciliation.md` — 기간 단위 대사 실패 (T3-⑨(b))
- `outbox-backlog.md` — 이벤트 유실에 의한 정산 누락 케이스
