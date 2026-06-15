# loan ↔ settlement 이벤트 계약 (Chunk 7 연동 가이드)

> loan-service 쪽 구현은 완료(`feat/loan-impl`). 본 문서는 **settlement-service 가 추가로 구현해야 할
> 상대편(saga counterpart)** 을 정의한다. settlement 코드는 다른 세션과 충돌 회피를 위해 보류됨.

## 토픽 & 페이로드

Outbox 폴러는 `aggregateType` + `eventType` 으로 토픽을 자동 라우팅한다
(`lemuel.<aggregate_lower>.<eventType_snake>`).

### settlement → loan (settlement 가 **발행**해야 함)

| 토픽 | 발행 시점 | 페이로드 | aggregateType / eventType |
|------|-----------|----------|---------------------------|
| `lemuel.settlement.created` | 정산 생성(batch create) | `{settlementId, sellerId, amount, dueDate}` | `Settlement` / `SettlementCreated` |
| `lemuel.settlement.confirmed` | 정산 확정(Confirm) | `{settlementId, sellerId, amount}` | `Settlement` / `SettlementConfirmed` |

- 현재 settlement 의 "SettlementConfirmed" 는 인프로세스 Spring 이벤트(ES 색인용)뿐 → **Outbox Kafka 발행을 신규 추가**해야 한다.
- 헤더 `event_id`(UUID) 필수 — loan 컨슈머의 멱등 키.

### loan → settlement (loan 이 **발행함**, settlement 가 **구독**해야 함)

| 토픽 | 발행 시점 | 페이로드 | 비고 |
|------|-----------|----------|------|
| `lemuel.loan.disbursement_requested` | 대출 실행 | `{loanId, sellerId, amount}` | settlement 가 payout 으로 셀러에게 `amount` 송금 |
| `lemuel.loan.repayment_applied` | 상환 차감 완료 | `{settlementId, sellerId, deducted}` | settlement 가 **순지급액 = amount − deducted** 로 payout |

## settlement 가 구현할 것 (Chunk 7)

1. **SettlementCreated / SettlementConfirmed Outbox 발행** 추가
   (`CreateSettlementFromPaymentService`, `ConfirmDailySettlementsService`). 기존 ES 이벤트 경로는 유지.
2. **`LoanDisbursementRequested` 컨슈머** → payout 으로 셀러에게 선지급액 송금.
3. **`LoanRepaymentApplied` 컨슈머** → 해당 정산건 payout 을 `amount − deducted` 로 net 지급.
   - `payout_status` 컬럼 추가(order-service 마이그레이션): `AWAITING_LOAN → PAID/HELD`.
4. **PayoutHoldScheduler**: `SettlementConfirmed` 발행 후 `LoanRepaymentApplied` 미수신이
   타임아웃 초과하면 payout 을 `HELD` 로 보류 + 알람. (차감 0 자동지급 금지 — 보수적)

## 정합성 규칙 (이미 loan 측에 구현됨)

- 멱등 3단: Outbox `event_id` UNIQUE → `processed_events(group,event_id)` PK →
  `loan_repayments.settlement_id` UNIQUE.
- 상환은 FIFO(오래된 대출 우선), `deducted = min(미상환잔액, 정산금)`.
- 대출 없는 셀러도 `deducted=0` 으로 `LoanRepaymentApplied` 발행 → settlement 전액 지급.

## 운영 메모

- loan-service 는 자체 DB `lemuel_loan`(스키마명은 `opslab` 재사용 — Outbox claim 네이티브 쿼리 하드코딩 정합).
- 독립 부팅 서비스(자체 `@SpringBootApplication`, port 8084 / mgmt 8085).
