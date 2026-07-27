# 시퀀스 — 결제 → Outbox → Kafka → 정산 (단일 Trace 경로)

> Lemuel 의 핵심 비동기 흐름. 분산 트레이싱 traceparent 가 모든 경계에 전파되어
> Tempo / Grafana 에서 단일 trace 로 가시화된다.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Web as PaymentController<br/>(order-service)
    participant Pay as CapturePaymentUseCase
    participant PG as PgRouter<br/>(TOSS/KCP/NICE/INICIS)
    participant DB as payments / outbox_events
    participant Poll as OutboxPublisher<br/>Scheduler (2s)
    participant Kafka as Kafka Topic<br/>lemuel.payment.captured
    participant Cons as PaymentEventKafkaConsumer<br/>(settlement-service)
    participant Set as Settlement Domain
    participant SetDB as settlements

    User->>Web: POST /payments/{id}/capture
    Note over Web: traceId 생성 (HTTP span)
    Web->>Pay: capturePayment(paymentId)
    Pay->>PG: capture(pgTransactionId, amount)
    Note over PG: prefix 로 원래 PG 어댑터 라우팅<br/>(@CircuitBreaker 보호)
    PG-->>Pay: ok

    Pay->>DB: UPDATE payments SET status=CAPTURED
    Pay->>DB: INSERT outbox_events<br/>(traceparent = 현재 trace)
    Note over DB: 같은 트랜잭션 — 도메인 변경과<br/>이벤트 기록 원자화

    Note over Web,DB: ✅ 트랜잭션 커밋

    Pay-->>Web: PaymentDomain
    Web-->>User: 200 OK

    rect rgb(245, 245, 220)
        Note over Poll,Cons: ── 비동기 경계 (2초 후) ──
        Poll->>DB: SELECT pending events
        DB-->>Poll: [event with traceparent]
        Note over Poll: traceparent 헤더 복원
        Poll->>Kafka: produce(record + traceparent header)
        Kafka-->>Cons: deliver
        Note over Cons: spring-kafka 자동 instrumentation<br/>이 traceparent 읽어 trace 합류
        Cons->>Cons: 멱등 체크 (processed_events PK)
        Cons->>Set: createSettlementFromPayment
        Set->>SetDB: INSERT settlements<br/>(commission_rate 스냅샷)
    end
```

## 멱등성 3 단 방어

| 레이어 | 메커니즘 | 실패 시 동작 |
|--------|----------|--------------|
| 프로듀서 | `outbox_events.event_id UUID UNIQUE` | DB 제약 위반 → 비즈니스 트랜잭션 롤백 |
| 폴러 | `app.kafka.enabled=true` 일 때만 publish + Kafka producer `enable.idempotence=true` | 동일 record 중복 발행 방지 |
| 컨슈머 | `processed_events(consumer_group, event_id)` PK | 같은 이벤트 재배달 시 즉시 ACK + 본문 처리 스킵 |
| 도메인 | `settlements.payment_id UNIQUE` | 위 3 단을 다 뚫어도 스키마가 최종 방어 |

## DLQ 분기 (재시도 한계 초과)

```mermaid
sequenceDiagram
    autonumber
    participant Poll as OutboxPublisher<br/>Scheduler
    participant DB as outbox_events
    participant DLQ as Kafka DLQ Topic<br/>lemuel.dlq.*
    participant Admin as OutboxAdminController
    actor Op as 운영자

    Poll->>DB: 발행 시도 (실패)
    Note over Poll,DB: markFailed() — retryCount++
    alt retryCount < 10
        Note over Poll: 다음 폴링 주기에 재시도
    else retryCount = 10
        Poll->>DLQ: publishToDlq(event + lastError header)
        Poll->>DB: status=FAILED
        Note over DLQ: 운영자 알람 (Slack/Pager)
        Op->>Admin: GET /admin/outbox/dlq
        Admin-->>Op: failed events list
        Op->>Admin: POST /dlq/{id}/retry 또는 /skip
        Admin->>DB: requeue() OR skip("사유")
    end
```
