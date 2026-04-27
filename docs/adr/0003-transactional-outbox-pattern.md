# ADR 0003 — Transactional Outbox 패턴

**Status:** Accepted
**Date:** 2026-04-23

## Context

결제 CAPTURED 시점에 외부 시스템(Kafka/Slack/ES)으로 이벤트를 발행해야 한다. 그러나:

- DB 커밋과 외부 발행을 같은 트랜잭션에 묶으면 **2PC** 또는 네트워크 실패 시 유실/중복 발생.
- 커밋 후 발행하면 발행 실패 시 이벤트 유실.
- 발행 후 커밋하면 커밋 실패 시 고스트 이벤트.

## Decision

**Outbox 테이블(V28 `outbox_events`)** 을 도입해 도메인 트랜잭션과 외부 발행을 분리한다.

```
[Tx A]  결제 CAPTURED + outbox_events INSERT (PENDING)
         └─ 원자적 커밋

[Polling]  OutboxPublisherScheduler 가 PENDING 을 읽어
           → ApplicationEventPublisher 또는 Kafka 로 발행
           → outbox_events.status = PUBLISHED 로 전이
```

**핵심 포인트:**

1. 외부 발행은 **At-least-once** — 컨슈머는 `event_id` 로 멱등 처리 필수 (`processed_events` 테이블 + `PaymentEventKafkaConsumer` 의 멱등 체크).
2. 발행 경로는 **포트 추상화** — `PublishEventPort` 인바운드 포트, `OutboxBackedEventPublisher` 가 기본 구현, `KafkaOutboxPublisher` 는 outbox 폴러의 외부 발행 어댑터.
3. `app.kafka.enabled=false` 면 ApplicationEventPublisher 경로 — 로컬 개발·단위 테스트 용이.
4. 환불 멱등성은 **`idempotency_key = payment-{id}-full`** 규칙 + `refunds` 테이블 UNIQUE 인덱스.

## Consequences

**Positive**
- 결제·정산 커밋과 외부 발행의 원자성 보장 (DB 커밋 성공 = 이벤트 영속화 성공).
- 외부 시스템 장애 시 PENDING 이 적체 → `OutboxPendingBacklog` 알림 (alert-rules.yml).
- Kafka 켜고 끄기 가능 (`app.kafka.enabled` 토글) → 로컬·CI 에서 외부 의존 제거.

**Negative / Trade-offs**
- 이벤트 전달에 폴링 지연(기본 2초).
- 컨슈머 멱등 처리 필수 — `processed_events` 테이블 추가(V29).

## Related

- ADR 0005 — Kafka vs ApplicationEvents
- Flyway V28 (outbox_events), V29 (processed_events)
- 불변식 #3: `count(outbox PaymentCaptured PUBLISHED) == count(settlements created)` — T3-⑨(b) reconciliation
