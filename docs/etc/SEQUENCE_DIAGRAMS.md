# 주요 시퀀스 다이어그램 / Key Sequence Diagrams

이 문서는 Lemuel 플랫폼의 핵심 비동기/동시성 흐름을 Mermaid 시퀀스 다이어그램으로 정리한다.
각 다이어그램의 참여자 이름은 실제 클래스명을 반영한다.

---

## 1. 결제 -> 정산 생성 (Outbox + Kafka)

결제가 승인(CAPTURED)되면 같은 트랜잭션에서 Outbox 이벤트를 기록하고,
`OutboxPublisherScheduler`(2초 주기)가 Kafka로 발행한다.
`settlement-service`의 `PaymentEventKafkaConsumer`가 이벤트를 수신해 정산을 자동 생성한다.

멱등성 3단 방어: outbox event_id UNIQUE -> processed_events PK -> settlements.payment_id UNIQUE.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant OrderSvc as OrderController<br/>(order-service)
    participant Capture as CapturePaymentUseCase
    participant TossPG as Toss Payments API
    participant DB as payments / outbox_events
    participant Poller as OutboxPublisherScheduler<br/>(2s 주기)
    participant Kafka as Kafka Topic<br/>lemuel.payment.captured
    participant Consumer as PaymentEventKafkaConsumer<br/>(settlement-service)
    participant SetSvc as CreateSettlementFrom<br/>PaymentUseCase
    participant SetDB as settlements / processed_events

    User->>OrderSvc: POST /payments/{id}/capture
    OrderSvc->>Capture: capturePayment(paymentId)
    Capture->>TossPG: POST /confirm (paymentKey, orderId, amount)
    TossPG-->>Capture: 승인 완료

    rect rgb(240, 248, 255)
        Note over Capture,DB: 단일 트랜잭션
        Capture->>DB: UPDATE payments SET status=CAPTURED
        Capture->>DB: INSERT outbox_events (event_id, PaymentCaptured)
    end

    Capture-->>OrderSvc: PaymentDomain
    OrderSvc-->>User: 200 OK

    rect rgb(245, 245, 220)
        Note over Poller,Consumer: 비동기 경계 (2초 후)
        Poller->>DB: SELECT * FROM outbox_events WHERE status=PENDING
        DB-->>Poller: [PaymentCaptured event]
        Poller->>Kafka: produce(PaymentCaptured + event_id header)
        Kafka-->>Consumer: deliver

        Consumer->>SetDB: processed_events 멱등 체크<br/>(consumer_group, event_id)
        alt 이미 처리됨
            Consumer->>Consumer: ACK + skip
        else 신규 이벤트
            Consumer->>SetSvc: createSettlementFromPayment<br/>(paymentId, orderId, amount)
            SetSvc->>SetDB: INSERT settlements (status=REQUESTED)<br/>+ INSERT processed_events
        end
    end
```

---

## 2. 부분 환불 + 정산 조정

부분 환불은 `RefundPaymentUseCase`에서 처리한다.
호출자가 `idempotencyKey`를 반드시 제공해야 하며, 트랜잭션 격리 수준은 `REPEATABLE_READ`이다.
환불 완료 후 `publishPaymentRefunded` 이벤트를 발행하면
settlement-service가 `SettlementAdjustment`(음수 금액 레코드)를 생성해 감사 추적을 보존한다.

```mermaid
sequenceDiagram
    autonumber
    actor Admin
    participant API as PaymentController<br/>(order-service)
    participant Refund as RefundPaymentUseCase<br/>(@Transactional REPEATABLE_READ)
    participant LoadPay as LoadPaymentPort
    participant LoadRef as LoadRefundPort
    participant PG as Toss Payments API
    participant DB as payments / refunds
    participant Event as PublishEventPort
    participant Kafka as Kafka Topic
    participant SetSvc as settlement-service
    participant AdjDB as settlement_adjustments

    Admin->>API: POST /payments/{id}/refund<br/>(amount, idempotencyKey)
    API->>Refund: refundPayment(id, amount, key)

    Refund->>LoadPay: loadById(paymentId)
    LoadPay-->>Refund: PaymentDomain (status=CAPTURED)

    Refund->>Refund: 상태 검증 (CAPTURED만 환불 가능)
    Refund->>Refund: refundableAmount 검증<br/>(amount <= totalAmount - refundedAmount)

    Refund->>LoadRef: findByPaymentIdAndIdempotencyKey
    alt 이미 COMPLETED
        LoadRef-->>Refund: Refund (completed)
        Refund-->>API: 현재 상태 반환 (멱등)
    else 신규 환불
        Refund->>DB: INSERT Refund (status=REQUESTED)

        Refund->>PG: refund(pgTransactionId, amount)
        PG-->>Refund: 환불 완료

        Refund->>DB: Refund.markCompleted()
        Refund->>DB: payment.addRefundedAmount(amount)

        alt 전액 환불 도달
            Refund->>DB: payment.status = REFUNDED
            Refund->>DB: order.status = REFUNDED
        end

        Refund->>Event: publishPaymentRefunded(paymentId, orderId,<br/>refundedAmount, refundAmount, refundId)
        Event->>Kafka: PaymentRefunded 이벤트 발행 (lemuel.payment.refunded)
        Kafka-->>SetSvc: PaymentRefundedSettlementAdjustConsumer 수신
        SetSvc->>AdjDB: SettlementAdjustment.ofRefund()<br/>(음수 레코드, refundId 1:1)
    end

    Refund-->>API: PaymentDomain
    API-->>Admin: 200 OK
```

---

## 3. PG 대사 (Reconciliation)

매일 PG사가 보내는 정산 CSV 파일과 내부 결제 원장을 1:1 비교한다.
`PgReconciliationMatcher`는 순수 도메인 로직(Spring 의존성 0)으로 5종 분류를 수행한다.
1원 미만 차이는 `AUTO_CORRECTED`로 자동 보정하고, 나머지는 운영자 승인을 대기한다.

```mermaid
sequenceDiagram
    autonumber
    actor Op as 운영자
    participant API as PgReconciliationController
    participant Svc as ReconcilePgFileService
    participant Parser as CsvPgFileParser
    participant Internal as InternalPaymentsForReconJdbcAdapter<br/>(→ OrderReconClient /internal/recon)
    participant Matcher as PgReconciliationMatcher<br/>(순수 도메인)
    participant DB as pg_reconciliation_runs<br/>/ discrepancies

    Op->>API: POST /admin/pg-reconciliation/files<br/>(provider, date, csv)
    API->>Svc: reconcile(provider, date, file)
    Svc->>Parser: parse(InputStream)
    Parser-->>Svc: List of PgTransactionRow

    Svc->>Internal: loadByCapturedDate(date)
    Note over Internal: order DB 직접 read 아님 — OrderReconClient 로<br/>order 내부 API(/internal/recon) 호출 (ADR 0020, cross-DB 0)
    Internal-->>Svc: List of InternalPaymentRow

    Svc->>Matcher: match(pgRows, internalRows)

    rect rgb(240, 250, 240)
        Note over Matcher: 5종 분류
        Matcher->>Matcher: 양쪽 일치 + 금액 동일 -> MATCHED
        Matcher->>Matcher: 차이 < 1원 -> ROUNDING_DIFF
        Matcher->>Matcher: 차이 >= 1원 -> AMOUNT_MISMATCH
        Matcher->>Matcher: PG에만 존재 -> MISSING_INTERNAL
        Matcher->>Matcher: 내부에만 존재 -> MISSING_PG
        Matcher->>Matcher: PG 파일 내 거래키 중복 -> DUPLICATE
    end

    Matcher-->>Svc: MatchResult

    Svc->>DB: INSERT pg_reconciliation_runs
    Svc->>DB: INSERT discrepancies

    alt ROUNDING_DIFF
        Svc->>DB: status = AUTO_CORRECTED (자동 보정)
    else AMOUNT_MISMATCH / MISSING_*
        Svc->>DB: status = PENDING (운영자 승인 대기)
    end

    Svc-->>API: ReconciliationRun
    API-->>Op: 대사 결과 요약

    Op->>API: GET /admin/pg-reconciliation/runs/{id}
    API-->>Op: PENDING discrepancy 목록

    alt 운영자 승인
        Op->>API: POST /discrepancies/{id}/approve
        Note over API: SettlementAdjustment 생성<br/>(역정산 트리거)
    else 운영자 거절
        Op->>API: POST /discrepancies/{id}/reject (reason 필수)
        Note over API: status=REJECTED + 사유 영구 기록
    end
```

---

## 4. SKU 재고 Optimistic Lock 재시도

`DecreaseVariantStockService`는 `@Version` 기반 Optimistic Lock으로 동시성을 제어한다.
각 재시도는 `TransactionTemplate`으로 새 트랜잭션을 열어 stale 1차 캐시를 방지한다.
백오프: 10ms -> 20ms -> 40ms -> 80ms -> 160ms. 5회 초과 시 `StockConcurrencyException`.

```mermaid
sequenceDiagram
    autonumber
    participant Caller as CreateMultiItemOrderService
    participant Stock as DecreaseVariantStockService
    participant TxTpl as TransactionTemplate<br/>(새 트랜잭션)
    participant DB as product_variants<br/>(@Version)
    participant Domain as ProductVariant.decreaseStock()
    participant Metric as Micrometer Counter

    Caller->>Stock: decrease(variantId, quantity)

    loop attempt 1..5 (MAX_ATTEMPTS)
        Stock->>TxTpl: execute(status -> ...)

        TxTpl->>DB: SELECT * FROM product_variants<br/>WHERE id = ? (version = N)
        DB-->>TxTpl: ProductVariant (version N)

        TxTpl->>Domain: decreaseStock(quantity)
        Note over Domain: stock < quantity 이면<br/>InsufficientStockException

        TxTpl->>DB: UPDATE product_variants<br/>SET stock = ?, version = N+1<br/>WHERE id = ? AND version = N

        alt UPDATE 1 row (성공)
            DB-->>TxTpl: ok
            TxTpl-->>Stock: ProductVariant (updated)
            Stock->>Metric: success.increment()
            Stock-->>Caller: ProductVariant
        else UPDATE 0 rows (version 충돌)
            DB-->>TxTpl: OptimisticLockingFailureException
            TxTpl-->>Stock: 예외 전파
            Stock->>Metric: retry.increment()

            alt attempt < 5
                Note over Stock: 지수 백오프 대기<br/>10ms << (attempt - 1)
                Note over Stock: 다음 루프에서 새 트랜잭션 시작
            else attempt = 5 (한계 초과)
                Stock->>Metric: failure.increment()
                Stock-->>Caller: StockConcurrencyException
            end
        end
    end
```

---

## 5. 장바구니 -> 체크아웃 -> 다건 주문

사용자가 장바구니에 담은 복수 상품을 한 번에 주문으로 전환하는 흐름이다.
`CheckoutCartService`가 `CreateMultiItemOrderService`를 호출하며,
같은 트랜잭션 안에서 재고 차감 + 주문 생성 + 장바구니 비우기가 원자적으로 수행된다.
실패 시 전체 롤백되어 장바구니가 유지되므로 사용자가 재시도할 수 있다.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant CartAPI as CartController
    participant Checkout as CheckoutCartService
    participant LoadCart as LoadCartPort
    participant OrderSvc as CreateMultiItemOrderService
    participant LoadProd as LoadProductPort
    participant LoadVar as LoadProductVariantPort
    participant Stock as DecreaseVariantStockService
    participant DB as DB (orders, order_items,<br/>product_variants, carts)
    participant Notify as SendOrderNotificationPort
    participant PayAPI as PaymentController

    User->>CartAPI: POST /users/{id}/cart/items<br/>(productId, variantId, qty)
    CartAPI->>DB: UPSERT cart_items
    CartAPI-->>User: 장바구니 상태

    User->>CartAPI: POST /users/{id}/cart/checkout
    CartAPI->>Checkout: checkout(userId)
    Checkout->>LoadCart: loadByUserId(userId)
    LoadCart-->>Checkout: Cart (items)

    alt 장바구니 비어있음
        Checkout-->>CartAPI: IllegalStateException
        CartAPI-->>User: 400 Bad Request
    end

    Checkout->>Checkout: cart.items -> List of Line 변환

    rect rgb(240, 248, 255)
        Note over Checkout,DB: 단일 트랜잭션 (@Transactional)
        Checkout->>OrderSvc: create(userId, lines)

        OrderSvc->>OrderSvc: 사용자 존재 검증

        loop 각 주문 라인
            OrderSvc->>LoadProd: findById(productId)
            LoadProd-->>OrderSvc: Product (name, price)

            alt variantId 존재 (SKU 라인)
                OrderSvc->>LoadVar: loadById(variantId)
                LoadVar-->>OrderSvc: ProductVariant (sku, additionalPrice)
                OrderSvc->>Stock: decrease(variantId, qty)
                Note over Stock: Optimistic Lock 재시도<br/>(위 4번 다이어그램 참조)
                Stock-->>OrderSvc: 차감 완료
                Note over OrderSvc: unitPrice = price + additionalPrice
            end

            OrderSvc->>OrderSvc: OrderItem.newItem() 생성
        end

        OrderSvc->>DB: INSERT orders<br/>(amount = sum of line amounts)
        OrderSvc->>DB: INSERT order_items
        OrderSvc->>Notify: 주문 확인 이메일 발송
        OrderSvc-->>Checkout: Order

        Checkout->>DB: cart.clear() + save
    end

    Checkout-->>CartAPI: Order
    CartAPI-->>User: 201 Created (orderId)

    Note over User,PayAPI: 이후 결제 흐름
    User->>PayAPI: POST /payments/{orderId}/capture
    Note over PayAPI: 위 1번 다이어그램으로 이어짐
```
