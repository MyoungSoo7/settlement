# Runbook — Cashflow Reconciliation Mismatch

**연결 알림:** `CashflowReconciliationMismatch` (critical), `CashflowReconciliationPersistentFailure` (critical)
**담당:** 백엔드 온콜 → 재무팀 에스컬레이션

## 증상

Prometheus `cashflow_reconciliation_mismatch_total{check="..."}` Counter 가 증가.
Slack `#alerts-critical` 에 알림 도착. Grafana Cashflow 대시보드 에서 `matched=false` 가 시각화됨.

## 1. 즉시 현황 파악

```bash
# 어느 check 가 깨졌는지 — 최근 1시간 기간대로 재조회
curl -u $ADMIN_TOKEN 'https://app.lemuel.internal/api/reports/cashflow?from=2026-04-23&to=2026-04-23&groupBy=day' \
  | jq '.reconciliation.mismatches'
```

`mismatches[].name` 별로 분기:

### (1) `payments_minus_refunds_equals_settlement`

- **의미:** 결제액 − 환불액 ≠ 정산 net + commission. 정산 생성 누락 또는 환불이 정산에 반영되지 않음.
- **조사:**
  ```sql
  -- 차이 값 반대편을 찾는 쿼리 — 해당 기간의 CAPTURED 결제 중 정산이 없는 건
  SELECT p.id, p.amount, p.captured_at
  FROM opslab.payments p
  LEFT JOIN opslab.settlements s ON s.payment_id = p.id
  WHERE p.status = 'CAPTURED'
    AND p.captured_at::date BETWEEN ? AND ?
    AND s.id IS NULL;
  ```
- **복구:** 누락된 정산을 수동 트리거 (`CreateSettlementFromPaymentService` 직접 호출) 또는 Kafka 리플레이.

### (2) `adjustments_equal_linked_refunds`

- **의미:** 역정산(adjustments.amount 의 절대값 합) 이 환불(refunds.COMPLETED) 합과 다름.
- **원인 후보:**
  - 환불 COMPLETED 인데 adjustment 가 미생성 (AdjustSettlementForRefundService 실패 로그 확인).
  - Adjustment 가 리프레시되지 않은 채 refund.amount 가 변경됨 (DB 직접 수정 시도 흔적 확인).
- **조사:**
  ```sql
  SELECT r.id AS refund_id, r.amount, r.status,
         sa.id AS adj_id, sa.amount AS adj_amount
  FROM opslab.refunds r
  LEFT JOIN opslab.settlement_adjustments sa ON sa.refund_id = r.id
  WHERE r.status = 'COMPLETED'
    AND r.completed_at::date BETWEEN ? AND ?
    AND (sa.id IS NULL OR ABS(sa.amount) <> r.amount);
  ```
- **복구:** `AdjustSettlementForRefundService.reconcile(refundId)` 수동 호출 (구현되어 있다면) 또는 SQL 보정.

### (3) `outbox_published_equals_settlements_created`

- **의미:** `PaymentCaptured` PUBLISHED 이벤트 수와 생성된 정산 수가 다름.
- **원인 후보:**
  - 컨슈머(`PaymentEventKafkaConsumer`) 실패·lag.
  - 기간 경계 시각 오차 (1~2건 차이는 허용 가능).
  - 배치 생성 정산과 이벤트 기반 정산이 중복 생성되어 역산 시 수 불일치.
- **조사:**
  ```bash
  # Kafka lag 확인
  kafka-consumer-groups.sh --bootstrap-server $KAFKA_BROKER \
    --describe --group settlement-consumer
  # processed_events 에서 처리된 이벤트 수와 outbox PUBLISHED 수 비교
  ```

## 2. 에스컬레이션

- 금액 차이 > 1,000,000 KRW → **즉시** 재무팀(`#finance-oncall`) + CFO.
- 1시간 내 복구 불가 → 장애 공지 + 포스트모템 채널 개설.

## 3. 사후

- 복구 완료 후 `/api/reports/cashflow` 재조회해 `matched=true` 확인.
- `cashflow_reconciliation_mismatch_total` Counter 가 추가 증가하지 않는지 24h 모니터링.
- 포스트모템 작성 → `docs/runbook/incidents/YYYY-MM-DD-<slug>.md`.

## 예방

- 통합 테스트 `SchemaIntegrationTest` + `PeriodReconciliationJdbcAdapter` 단위 테스트 강화.
- 신규 정산 플로우 추가 시 대사 불변식 추가 검토.
