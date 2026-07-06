---
name: idempotency-and-events
description: Outbox 발행·Kafka 컨슈머·멱등성 3단 방어 규칙. 이벤트 발행/구독 코드를 작성하거나 중복 처리 버그를 조사할 때 로드.
---

# 이벤트·멱등성 규칙 (ADR 0003, 0017)

## 발행 — 반드시 Outbox 경유

- 비즈니스 tx 안에서 `kafkaTemplate.send()` 직접 호출 금지. 같은 DB tx 에서
  `outbox_events` INSERT (event_id UUID) → 멀티워커 폴러가 `FOR UPDATE SKIP LOCKED` claim 후 발행.
- 이유: DB 커밋과 발행의 원자성. 직접 발행은 "커밋 실패했는데 이벤트는 나감" 사고를 만든다.
- 관측: `outbox.pending.count`(적체), `outbox.failed.count`, `outbox.dlq.published` — MCP `outbox_status`.

## 구독 — 멱등 체크는 컨슈머 코드의 첫 줄

새 컨슈머 필수 골격:

```java
@KafkaListener(topics = "...", groupId = GROUP)
public void on(Event e) {
    if (!processedEventPort.markIfNew(GROUP, e.eventId())) return; // ← 멱등 체크 (같은 tx)
    // ... 비즈니스 로직
}
```

- `processed_events` PK = `(consumer_group, event_id)` — 그룹별 독립 멱등.
- 멱등 체크와 비즈니스 로직은 **같은 DB 트랜잭션** — 체크만 커밋되고 로직이 롤백되면 이벤트 유실이다.

## 3단 멱등 방어 (어느 층이 빠졌는지로 버그 위치 특정)

| 층 | 방어 | 뚫리면 생기는 증상 |
|---|---|---|
| 1 | `outbox_events.event_id UUID UNIQUE` | 같은 이벤트 중복 발행 |
| 2 | `processed_events PK (consumer_group, event_id)` | 컨슈머 중복 처리 |
| 3 | `settlements.payment_id UNIQUE` | 최후 방어 — 중복 정산 생성 시 DB 제약 위반 |

중복 정산 버그 조사 시: 3층 제약 위반 로그가 있으면 1·2층이 뚫린 것 — event_id 생성 위치와
컨슈머 tx 경계부터 확인하라.

## 실패 처리 — DLT + 리플레이 (ADR 0017)

- 컨슈머 예외는 재시도 후 DLT 토픽으로. DLT 적체는 "조용한 데이터 유실" — 정기 확인 대상.
- 리플레이 시 멱등 체크 덕에 이미 처리된 이벤트는 무해하게 스킵된다 — 이것이 멱등을 강제하는 이유.
- 스키마 변경은 하위호환만 허용 (ADR 0022) — 필드 삭제/타입 변경은 신규 토픽 버전으로.

## 로컬 주의사항

- rpk 로 직접 produce 테스트 시 `-z none` 필수 (Redpanda 이미지에 snappy 네이티브 없음).
