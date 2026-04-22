# Portfolio 문구 — 정산·결제 도메인 아키텍처

정직하게 **현재 구현**을 반영한 이력서/포트폴리오 문구.
원안(2026.01~02 기간 제출본)을 실체에 맞게 바로잡고, 향후 확장 계획을 분리해 기술했다.

---

## 프로젝트: 정산·결제 도메인 아키텍처

- **URL**: https://jen.lemuel.co.kr
- **기간**: 2026.01 ~ 2026.02
- **역할**: 정산 도메인 설계 및 이벤트 기반 정산 파이프라인 구현
- **목적**: 배치 중심 정산 구조의 결합도·운영 가시성·확장성을 단계적으로 개선

### 구현 내용 (7개 전부 실제 작동)

1. **Transactional Outbox 패턴 적용** — `outbox_events` 테이블(V28)과
   `OutboxPublisherScheduler` 폴러로 도메인 트랜잭션과 이벤트 발행의
   원자성 확보. "커밋되지 않은 이벤트 유출" / "커밋된 이벤트 누수" 차단.

2. **배치 → 이벤트 기반 정산 처리 구조 전환** — Payment `CAPTURED` 이벤트를
   Outbox → Kafka(`lemuel.payment.captured`) → `PaymentEventKafkaConsumer` →
   `CreateSettlementFromPaymentService` 파이프라인으로 승격. 기존
   `CapturePaymentUseCase` 의 정산 직접 호출 제거. K8s CronJob 은 누락분
   reconcile 전용으로 격하.

3. **3 단 멱등성 설계** — (a) 환불 Idempotency-Key (`refunds` unique),
   (b) outbox `event_id` UUID unique (프로듀서 중복 발행 방지),
   (c) `processed_events(consumer_group, event_id)` PK (컨슈머 재수신 방지),
   (d) `settlements.payment_id` UNIQUE 스키마 최종 방어.

4. **Prometheus + Alertmanager Slack 알림** — Micrometer p50/p95/p99 히스토그램,
   Alertmanager 룰로 배치 실패/지연 / outbox 적체(>1k, >10k) / outbox 발행 p95 지연 /
   DB 커넥션 고갈 감지. Slack webhook 연동.

5. **Kafka 기반 이벤트 비동기 처리** — Redpanda(Kafka API 호환) 브로커,
   `spring-kafka` 도입. `KafkaOutboxPublisher` 로 outbox→토픽 발행 (acks=all,
   idempotence=true, lz4 압축, aggregateId 키 해시 파티셔닝). 컨슈머는 수동
   ack + read_committed + 멱등 테이블 체크. Testcontainers Kafka 로 E2E 검증.

6. **Docker Multi-stage + GitHub Actions CI/CD** — Gradle JDK 25 빌더 +
   Temurin JRE Alpine 런타임 분리. paths-filter → build → JaCoCo → SonarCloud →
   Snyk → GHCR 푸시 체인.

7. **Hexagonal Architecture + ArchUnit 경계 강제** — 10개 도메인을
   port/adapter 로 분리, ArchUnit 규칙 4종을 테스트로 강제. Outbox/Kafka 도
   동일 구조 준수 (common/outbox/{domain, application, adapter.in.kafka,
   adapter.out.event, adapter.out.persistence}).

### 기술

Java 25, Spring Boot 3.5.10, PostgreSQL 17, Elasticsearch 8.17, **Redpanda
(Apache Kafka 호환) + spring-kafka**, Spring Batch, Flyway V1~V29, Caffeine,
iText 8, Micrometer + Prometheus + Grafana, Docker, GitHub Actions,
Kubernetes (ArgoCD + CronJob + StatefulSet), JUnit 5 + Testcontainers
(Postgres + Kafka) + ArchUnit.

### 성과

- **배치 → 이벤트 기반 구조 전환** — Payment CAPTURED 이벤트가 Kafka 로
  흘러 정산이 자동 생성. 배치는 reconcile 전용으로 격하 → 정산 지연 감소,
  결제 트랜잭션과 정산 서비스 가용성 분리.
- **Outbox 로 트랜잭션 일관성 + 이벤트 신뢰성** — PG/DB 커밋과 Kafka 발행이
  분리되어 커밋 이후 이벤트 발행이 보장.
- **3 단 멱등성** — 중복 결제 환불, 중복 정산 생성, 중복 이벤트 재처리를
  각 레이어에서 원천 차단.
- **운영 가시성** — outbox 적체, 발행 지연, 컨슈머 lag 을 Slack 룰로 감지.
- **테스트 자동화** — Testcontainers Postgres/Kafka 로 CI 에서 스키마 정합성
  + Kafka E2E 파이프라인을 자동 검증.
