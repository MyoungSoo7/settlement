# 주요 시퀀스 다이어그램

> ⚠️ **[STALE — MSA 분리 이전(모놀리스) 흐름]** 이 문서의 다이어그램들은 order/settlement 가 한 프로세스·한 DB 를
> 공유하던 시절의 동기 흐름을 그린 것이다. 현재 아키텍처와 다르다:
> - 결제→정산은 **동기 호출이 아니라 Outbox + Kafka 비동기**(`lemuel.payment.captured` → `PaymentEventKafkaConsumer`)로
>   생성된다. settlement 는 별도 서비스·별도 DB(settlement_db)이며 `TossPaymentService` 가 `SettlementDomain` 을 직접
>   호출하지 않는다.
> - 정산 상태 종료값은 `COMPLETED` 가 아니라 **`DONE`** 이다(`SettlementStatus`).
> - `CreateDailySettlementsService`(#3) 처럼 settlement 가 `PaymentRepository` 를 직접 읽는 일일 배치는 없다 —
>   정산 생성은 이벤트 드리븐이고, 확정만 Spring Batch(`SettlementConfirmJobConfig`)로 처리한다.
>
> 최신 흐름은 `docs/etc/SEQUENCE_DIAGRAMS.md`, `docs/etc/SEQUENCE-ORDER-VS-REFUND.md`,
> `docs/diagrams/sequence-*.md` 를 참조. 아래는 이력 보존용.

## 1. Toss 결제 확인 플로우

```mermaid
sequenceDiagram
    actor Client
    participant PaymentController
    participant TossPaymentService
    participant TossAPI as Toss API
    participant PaymentDomain
    participant SettlementDomain
    participant Database

    Client->>PaymentController: POST /payments/confirm (orderId, paymentKey, amount)
    PaymentController->>TossPaymentService: confirmPayment(request)
    TossPaymentService->>PaymentDomain: createPayment(READY)
    PaymentDomain-->>TossPaymentService: Payment(READY)
    TossPaymentService->>TossAPI: POST /confirm (paymentKey, orderId, amount)
    TossAPI-->>TossPaymentService: 결제 승인 응답
    TossPaymentService->>PaymentDomain: authorize(paymentKey)
    PaymentDomain-->>TossPaymentService: Payment(AUTHORIZED)
    TossPaymentService->>PaymentDomain: capture()
    PaymentDomain-->>TossPaymentService: Payment(CAPTURED)
    TossPaymentService->>SettlementDomain: createSettlement(payment)
    SettlementDomain-->>TossPaymentService: Settlement
    TossPaymentService->>Database: save(payment, settlement)
    Database-->>TossPaymentService: saved
    TossPaymentService-->>PaymentController: PaymentResponse
    PaymentController-->>Client: 200 OK (결제 완료)
```

## 2. 환불 플로우

```mermaid
sequenceDiagram
    actor Client
    participant PaymentController
    participant RefundPaymentUseCase
    participant PgClientPort
    participant PaymentDomain
    participant OrderDomain
    participant SettlementDomain
    participant Database

    Client->>PaymentController: POST /payments/{id}/refund (reason, amount)
    PaymentController->>RefundPaymentUseCase: execute(refundRequest)
    RefundPaymentUseCase->>PgClientPort: refund(paymentKey, reason, amount)
    PgClientPort-->>RefundPaymentUseCase: PG 환불 응답
    RefundPaymentUseCase->>PaymentDomain: refund()
    PaymentDomain-->>RefundPaymentUseCase: Payment(REFUNDED)
    RefundPaymentUseCase->>OrderDomain: updateStatus(REFUNDED)
    OrderDomain-->>RefundPaymentUseCase: Order(REFUNDED)
    RefundPaymentUseCase->>SettlementDomain: adjustForRefund(payment)
    SettlementDomain-->>RefundPaymentUseCase: 정산 금액 차감 완료
    RefundPaymentUseCase->>Database: save(payment, order, settlement)
    Database-->>RefundPaymentUseCase: saved
    RefundPaymentUseCase-->>PaymentController: RefundResponse
    PaymentController-->>Client: 200 OK (환불 완료)
```

## 3. 일일 정산 배치

```mermaid
sequenceDiagram
    participant Scheduler as Scheduler (매일 02:00)
    participant CreateDailySettlementsService
    participant PaymentRepository
    participant Settlement
    participant SettlementRepository
    participant Elasticsearch

    Scheduler->>CreateDailySettlementsService: execute()
    CreateDailySettlementsService->>PaymentRepository: loadCapturedPayments(전일 날짜)
    PaymentRepository-->>CreateDailySettlementsService: List<Payment>

    loop 각 결제 건에 대해
        CreateDailySettlementsService->>Settlement: createFromPayment(payment, 수수료율 3%)
        Settlement-->>CreateDailySettlementsService: Settlement(REQUESTED)
        CreateDailySettlementsService->>SettlementRepository: save(settlement)
        SettlementRepository-->>CreateDailySettlementsService: saved
        CreateDailySettlementsService->>Elasticsearch: indexSettlement(settlement)
        Elasticsearch-->>CreateDailySettlementsService: indexed
    end

    CreateDailySettlementsService-->>Scheduler: 배치 완료 (처리 건수)
```

## 4. 정산 확정 배치

```mermaid
sequenceDiagram
    participant Scheduler as Scheduler (매일 03:00)
    participant ConfirmDailySettlementsService
    participant SettlementRepository
    participant Settlement

    Scheduler->>ConfirmDailySettlementsService: execute()
    ConfirmDailySettlementsService->>SettlementRepository: loadRequestedSettlements()
    SettlementRepository-->>ConfirmDailySettlementsService: List<Settlement>

    loop 각 정산 건에 대해
        ConfirmDailySettlementsService->>Settlement: startProcessing()
        Settlement-->>ConfirmDailySettlementsService: Settlement(PROCESSING)

        alt 처리 성공
            ConfirmDailySettlementsService->>Settlement: complete()
            Settlement-->>ConfirmDailySettlementsService: Settlement(DONE)
        else 처리 실패
            ConfirmDailySettlementsService->>Settlement: fail(reason)
            Settlement-->>ConfirmDailySettlementsService: Settlement(FAILED)
        end

        ConfirmDailySettlementsService->>SettlementRepository: save(settlement)
        SettlementRepository-->>ConfirmDailySettlementsService: saved
    end

    ConfirmDailySettlementsService-->>Scheduler: 배치 완료 (성공/실패 건수)
```
