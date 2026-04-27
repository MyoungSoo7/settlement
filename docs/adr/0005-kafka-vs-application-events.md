# ADR 0005 — Kafka 도입과 ApplicationEvents 공존

**Status:** Accepted
**Date:** 2026-04-23

## Context

초기에는 단일 애플리케이션 내에서 `ApplicationEventPublisher` 로 결제→정산 이벤트를 전달했다. 그러나:

- 로컬·단일 JVM 외 환경(멀티 pod, 다른 팀의 소비자)에서는 동작 불가.
- 부하 증가에 따라 정산 생성을 별도 워커에서 처리할 필요.
- ES 인덱싱, 알림 등 downstream 소비자가 늘어남.

## Decision

**Kafka(Redpanda 호환) 를 도입**하되, 기존 ApplicationEvents 경로는 유지한다.

**토글 스위치:** `app.kafka.enabled=true/false`
- `false` (기본): `@TransactionalEventListener` 로 동일 JVM 내에서 처리. 테스트·로컬·CI 용이.
- `true`: `KafkaOutboxPublisher` 가 outbox PENDING 이벤트를 Kafka 에 발행, `PaymentEventKafkaConsumer` 가 토픽 구독 → 정산 생성.

**토픽 명명:** `lemuel.<aggregate>.<event_snake>` (예: `lemuel.payment.captured`)

**브로커 선택: Redpanda**
- Kafka protocol 호환.
- 단일 바이너리 배포, ZooKeeper 불필요 → 로컬 compose 간단.
- 프로덕션에서 Apache Kafka 로 바꾸기 쉬움 (프로토콜 동일).

## Consequences

**Positive**
- 로컬 개발에서 Kafka 의존 제거 가능 (토글 off).
- 프로듀서·컨슈머가 포트·어댑터 뒤에 있어 교체 용이.
- Outbox 패턴(ADR 0003)과 결합해 At-least-once 보장.

**Negative / Trade-offs**
- 이중 경로 유지 비용 (단위 테스트가 양쪽 모두 커버해야 함).
- Kafka 오토 컨피그 모듈 (`spring-boot-starter-kafka`) 이 Boot 4 에서 별도 declare 필요 — ADR 0009 참조.

## Related

- ADR 0003 — Transactional Outbox 패턴
- ADR 0009 — Boot 4 모듈 분리 대응
- 구성: `application.yml` `app.kafka.*`, `spring.kafka.*`
