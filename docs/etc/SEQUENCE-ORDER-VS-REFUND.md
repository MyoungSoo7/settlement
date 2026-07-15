# 정상 주문 vs 환불 역정산 — 시퀀스 대비

> 같은 도메인 백본(**상품 → 주문 → 결제 → 정산**) 위에서,
> happy-path(정상 주문)와 예외-path(환불 역정산)를 나란히 비교한다.
> 참여자 이름은 실제 클래스/토픽명을 반영했고, 미구현/미연결 구간은 ⚠️ 로 표시했다.

---

## A. 정상 주문 → 정산 생성 (happy path)

사용자가 상품을 주문·결제하면, 결제 승인(CAPTURED)이 **같은 트랜잭션에서 Outbox 이벤트**를
남기고, 로컬 폴러가 Kafka로 발행 → settlement-service가 수신해 **정산을 자동 생성**한다.

```mermaid
sequenceDiagram
    autonumber
    actor User as 사용자
    participant Web as OrderController<br/>(order-service)
    participant Cart as Cart / Order
    participant Cap as CapturePaymentUseCase
    participant Toss as Toss Payments API
    participant ODB as payments / outbox_events
    participant Poll as OutboxPublisherScheduler
    participant K as Kafka<br/>lemuel.payment.captured
    participant Con as PaymentEventKafkaConsumer<br/>(settlement-service)
    participant Set as CreateSettlementFrom<br/>PaymentUseCase
    participant SDB as settlements / processed_events

    User->>Web: 상품 조회 → 장바구니 → 주문 생성
    Note over Cart: Order CREATED
    User->>Web: POST /payments/{id}/capture
    Web->>Cap: capturePayment(paymentId)
    Cap->>Toss: POST /confirm (paymentKey, amount)
    Toss-->>Cap: 승인 완료

    rect rgb(235,245,255)
        Note over Cap,ODB: 단일 트랜잭션 (원자성)
        Cap->>ODB: UPDATE payments SET status=CAPTURED
        Cap->>ODB: INSERT outbox_events(event_id, PaymentCaptured)
    end
    Note over Cart: Order PAID

    Poll->>ODB: PENDING 이벤트 polling
    Poll->>K: publish PaymentCaptured (acks=all, key=aggregateId)

    K->>Con: consume PaymentCaptured
    rect rgb(235,255,240)
        Note over Con,SDB: 멱등 3단 방어
        Con->>SDB: processed_events PK 멱등 체크
        Con->>Set: createFromPayment(paymentId, amount)
        Set->>SDB: INSERT settlements (payment_id UNIQUE)
        Con->>SDB: INSERT processed_events(group, event_id)
    end
    Note over SDB: Settlement REQUESTED → 수수료 차감 → netAmount 확정<br/>(이후 payout / ledger POSTED 로 이어짐)
```

핵심 방어선:
- **Outbox**: 도메인 커밋과 이벤트 발행 원자성 (커밋 안 된 이벤트 유출 / 커밋된 이벤트 누수 차단)
- **멱등 3단**: `outbox_events.event_id` UNIQUE → `processed_events` PK → `settlements.payment_id` UNIQUE

---

## B. 환불 → 정산 역정산 (예외 path)

환불은 **결제 완료(CAPTURED) 이후**에 발생한다. 정산이 이미 생성/확정됐을 수 있어
단순 차감이 아니라 **역정산(조정) + 복식부기 역분개**가 필요하다.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as 관리자/사용자
    participant Web as RefundController<br/>(order-service)
    participant Ref as RefundPaymentUseCase<br/>(REPEATABLE_READ)
    participant Toss as Toss Payments API
    participant ODB as payments / refunds / outbox_events
    participant K as Kafka<br/>lemuel.payment.refunded
    participant Adj as AdjustSettlementForRefundService<br/>(settlement-service)
    participant SDB as settlements / settlement_adjustments
    participant Led as ReverseEntryService<br/>(ledger)

    Admin->>Web: POST /payments/{id}/refund (Idempotency-Key)
    Web->>Ref: refund(paymentId, amount, key)

    rect rgb(255,245,235)
        Note over Ref,ODB: 멱등 + 상태 가드
        Ref->>Ref: Idempotency-Key 검증 (refunds UNIQUE)
        Ref->>Ref: 상태 가드 — CAPTURED 만 환불 허용
        Ref->>Toss: POST /cancel (amount)
        Toss-->>Ref: 환불 완료
        Ref->>ODB: 전액→REFUNDED / 부분→refundedAmount 누적
        Ref->>ODB: INSERT outbox_events(PaymentRefunded)
    end
    Note over ODB: 전액 환불 도달 시에만 Order REFUNDED 전이

    ODB-->>K: (Outbox 폴러) publish PaymentRefunded<br/>(payload: paymentId·orderId·refundedAmount·refundAmount·refundId)

    Note over K,Adj: PaymentRefundedSettlementAdjustConsumer<br/>(group=lemuel-settlement-refund-adjust) 가 실제 구독·조정

    K-->>Adj: consume PaymentRefunded
    rect rgb(255,235,240)
        Note over Adj,Led: 역정산 — 비관적 락으로 lost update 방지
        Adj->>SDB: findByPaymentIdForUpdate (SELECT ... FOR UPDATE)
        Adj->>SDB: consumeHoldbackForRefund — 보류금 우선 차감
        Adj->>SDB: adjustForRefund — netAmount 재계산(running total)
        Adj->>SDB: INSERT settlement_adjustments (음수 레코드, refundId 1:1)
        Adj->>Led: enqueueReverse(settlementId, refundId, amount)
    end

    rect rgb(240,240,255)
        Note over Led: 복식부기 역분개
        Led->>Led: REFUND_REVERSED 차/대변 기록
        Led->>Led: LedgerEntry POSTED → REVERSED
    end
```

핵심 방어선:
- **Idempotency-Key**(`refunds` UNIQUE) — 환불 재요청 중복 차감 방지
- **상태 가드** — `CAPTURED` 상태에서만 환불 허용
- **Pessimistic Lock**(`findByPaymentIdForUpdate`) — 동시 환불 2건의 lost update 방지
- **Holdback 우선 차감** — 보류금에서 먼저 빼 셀러 추가 부담/마이너스 정산 흡수
- **SettlementAdjustment 음수 레코드** — 정산 원본을 건드리지 않고 역정산 이력 보존(감사 추적)
- **Ledger 역분개**(REFUND_REVERSED, POSTED→REVERSED) — 차대 균형 유지

---

## C. 두 흐름의 대비 요약

| 구분 | A. 정상 주문 | B. 환불 역정산 |
|------|-------------|----------------|
| 트리거 | `PaymentCaptured` | `PaymentRefunded` (결제 후) |
| 정산 작용 | settlements **생성** | settlement_adjustments **음수 보정** + netAmount 재계산 |
| 원장 | (정상 분개 / POSTED) | **역분개** REFUND_REVERSED, POSTED→REVERSED |
| 동시성 방어 | 멱등 3단 (outbox·processed·UNIQUE) | + Idempotency-Key + **Pessimistic Lock** + Holdback 우선차감 |
| 주문 상태 | CREATED→PAID | (전액 시) →REFUNDED |
| 구현 상태 | ✅ E2E 동작 | ✅ E2E 동작 — 환불 이벤트 컨슈머(`PaymentRefundedSettlementAdjustConsumer`)까지 연결 |

---

## 부록 — 정직성 노트 (실체 반영)

- **A 흐름**은 order→Kafka→settlement 까지 컨슈머(`PaymentEventKafkaConsumer`)가
  `payment-captured` 토픽을 실제 수신해 동작한다.
- **B 흐름**의 order-service 측(환불 처리 + `PaymentRefunded` Outbox 발행)과
  settlement-service 측 역정산 로직(`AdjustSettlementForRefundService`)·역분개(ledger enqueueReverse)가
  모두 구현·테스트돼 있고, **두 흐름을 잇는 컨슈머까지 연결돼 E2E 로 동작한다**.
- settlement-service 에 `lemuel.payment.refunded` 를 구독하는 `@KafkaListener` 가 두 개 있다:
  `PaymentRefundedSettlementAdjustConsumer`(group=`lemuel-settlement-refund-adjust`, 실제 정산 조정·역분개)와
  `PaymentRefundedViewConsumer`(group=`lemuel-settlement-payment-view`, 프로젝션 뷰 환불액 갱신) — 그룹을 분리해
  한쪽 실패가 다른 쪽을 막지 않는다.
- `PaymentRefunded` Outbox payload 는 `paymentId`·`orderId`·`refundedAmount`·`refundAmount`(건별 delta)·`refundId` 를
  담는다(`OutboxBackedEventPublisher.publishPaymentRefunded`). 조정 컨슈머는 `refundAmount` 를 우선 사용하고,
  레거시 페이로드(누적 `refundedAmount` 만)는 정산 기반영치와의 차이로 delta 를 복원한다.
