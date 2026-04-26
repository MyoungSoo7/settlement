# ADR-001: 환불을 정산 원본 mutation 대신 SettlementAdjustment + Ledger 분개로 처리

## Status
Accepted (2026-04-26)

## Context
정산이 DONE 상태가 된 이후 발생하는 환불은 회계 원장(audit log)의
immutability 원칙을 깨뜨리지 않고 처리되어야 한다. 이전 구조는
`Settlement.adjustForRefund()`가 원 정산 레코드를 in-place 수정하여:
- 정산 합계의 시계열 추적 불가
- 음수 정산 표현 불가
- 회계감사 시 "최초 정산 금액"이 무엇이었는지 알 수 없음
- `RefundExceedsPaymentException`이 선언만 있고 throw 0건이어서 초과 환불 invariant 미보호
- `Idempotency-Key` 헤더가 컨트롤러에서 받지만 UseCase에 전달조차 안 되어 멱등성 미보장
- 부분환불 컨트롤러가 `// TODO` 주석 후 전액 환불을 호출하여 사실상 미구현

## Decision
1. **Refund 도메인 신설**: `Refund` 애그리거트 + `RefundStatus` enum. `Refund.request()` 정적 팩토리에서 `paymentId>0`, `amount>0`, `idempotencyKey` 비공백 invariant 강제.
2. **PaymentDomain.requestRefund(amount)**: 누적 환불액(`refundedAmount += amount`) 검증 + 누적이 결제액과 같아지면 status REFUNDED 자동 전이. 누적이 초과하면 `RefundExceedsPaymentException` throw.
3. **멱등성 이중 보장**:
   - DB UNIQUE 인덱스 `(payment_id, idempotency_key)` (V4 마이그레이션)
   - `LoadRefundPort.findByPaymentIdAndIdempotencyKey()`로 UseCase 진입 시 기존 Refund 반환 → PG 재호출/INSERT 0건
4. **SettlementAdjustment 도메인 신설**: 환불 1건당 `SettlementAdjustment` 레코드를 신규 INSERT. 원 `Settlement`는 변경하지 않음 (immutable after creation).
5. **부호 변환 매퍼**: 도메인 `amount`는 양수, JPA `amount`는 음수(V4 SQL `chk_adjustments_amount CHECK (amount < 0)` 충족). `SettlementAdjustmentMapper.toJpa`에서 `negate()`, `toDomain`에서 `abs()`.
6. **Ledger 분개 호출**: 환불 시 `LedgerService.recordRefundProcessed`로 `SELLER_PAYABLE Dr + PLATFORM_COMMISSION Dr / PLATFORM_CASH Cr` 분개. 비례 수수료 환급 = `commission * (refundAmount / paymentAmount)`, HALF_UP, scale 2.
7. **Ledger 멱등성**: `refundId`를 idempotencyKey로 사용. 동일 환불 재처리 시 `DuplicateJournalEntryException`으로 중복 분개 차단.
8. **컨트롤러 단일 엔드포인트**: 기존 `/full`, `/partial` 엔드포인트 삭제. `POST /api/refunds/{paymentId}?refundAmount=...` 단일 진입. refundAmount가 결제 전액이면 자동 전체 환불.
9. **Fail-fast**: 기존 `try/catch (정산 조정 실패 무시)` 패턴 제거. 정산 조정 실패 시 환불 전체 롤백.

## Consequences

### Positive
- 회계 무결성: 원본 정산 immutable, audit log로 시계열 추적 가능
- 차변/대변 균형 검증이 분개 단위로 보장 (`UnbalancedJournalEntryException`)
- refundId 기반 멱등성으로 동일 환불 재처리 시 분개 중복 방지
- 부분환불이 진짜로 작동 (이전: TODO 후 전액 호출)
- 초과 환불 도메인 invariant 보호 (`RefundExceedsPaymentException`)
- 단위/통합 테스트 13건+ 추가로 회귀 안전망 강화

### Negative
- 정산 실 잔액 조회 시 JOIN 또는 집계 필요 (`Settlement.netAmount + SUM(SettlementAdjustment.amount)`). 추후 phase에서 read model 도입 고려.
- `Settlement.adjustForRefund()` 메서드 제거로 외부 호출자가 있었다면 깨짐 (현 시점 호출자 0건).
- `PaymentDomain.refund()`, `PaymentDomain.addRefundedAmount()` 제거 (dead code).
- 정산 조정 실패 = 환불 전체 롤백 = PG가 이미 환불 처리한 상태에서 우리 DB만 롤백되는 시나리오 발생 가능. 보상 트랜잭션은 본 ADR 범위 외 (Outbox 패턴 등 후속 phase).

### Out-of-Scope (별도 ADR/plan으로)
- VAT/원천징수 분리
- 영업일/공휴일 보정
- WEEKLY/MONTHLY BillingPeriod 모델링
- SettlementStatus enum 11개 정리
- 송금/이체 어댑터 (펌뱅킹)
- Toss API 멱등성 헤더 실 전달
- Spring Batch chunk-oriented 전환
- Outbox 패턴 / Kafka 비동기

## References
- Plan: `docs/superpowers/plans/2026-04-26-partial-refund-reverse-settlement-ledger.md`
- V4 마이그레이션: `src/main/resources/db/migration/V4__refunds_and_settlement_adjustments.sql`
- V35 마이그레이션 (Ledger 테이블): `src/main/resources/db/migration/V35__create_ledger_tables.sql`
- 핵심 코드:
  - `payment/domain/Refund.java`
  - `payment/domain/PaymentDomain.java` (`requestRefund`)
  - `settlement/domain/SettlementAdjustment.java`
  - `settlement/application/service/AdjustSettlementForRefundService.java`
  - `payment/application/RefundPaymentUseCase.java`
  - `ledger/application/service/LedgerService.java` (`recordRefundProcessed`)
