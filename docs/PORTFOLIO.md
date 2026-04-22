# Portfolio 문구 — 정산·결제 도메인 아키텍처

정직하게 **현재 구현**을 반영한 이력서/포트폴리오 문구.
원안(2026.01~02 기간 제출본)을 실체에 맞게 바로잡고, 향후 확장 계획을 분리해 기술했다.

---

## 프로젝트: 정산·결제 도메인 아키텍처

- **URL**: https://jen.lemuel.co.kr
- **기간**: 2026.01 ~ 2026.02
- **역할**: 정산 도메인 설계 및 이벤트 기반 정산 파이프라인 구현
- **목적**: 배치 중심 정산 구조의 결합도·운영 가시성·확장성을 단계적으로 개선

### 구현 내용 (현재 기준으로 실체화한 7개)

1. **Transactional Outbox 패턴 적용** — `outbox_events` 테이블과
   `OutboxPublisherScheduler` 폴러를 도입. 도메인 트랜잭션과 외부 이벤트
   발행의 원자성을 보장해 "커밋되지 않은 이벤트 유출" / "커밋된 이벤트
   누수" 를 차단.

2. **배치 후속 작업의 이벤트 기반 분리** — 정산 생성·확정 완료 시
   Elasticsearch 인덱싱을 Spring ApplicationEventPublisher 기반 비동기
   처리로 분리. 배치 주 경로는 Spring Batch + K8s CronJob 로 유지.

3. **다층 멱등성 설계** — (a) 환불 Idempotency-Key (payments 테이블
   unique 제약 + 도메인 로직의 키 검증), (b) 정산 payment_id unique
   제약으로 중복 생성 차단, (c) outbox event_id UUID unique 로 재발행
   중복 방지.

4. **Prometheus + Alertmanager Slack 알림** — Micrometer p50/p95/p99
   histogram, Alertmanager 룰로 배치 실패·처리 지연·outbox 적체·DB
   커넥션 고갈 감지. Slack webhook 연동.

5. **이벤트 확장을 위한 포트 추상화** — 외부 발행은
   `PublishExternalEventPort` 뒤에 두어 Kafka 도입 시 도메인·스케줄러
   수정 없이 어댑터만 교체. 현재 구현체는
   `ApplicationEventOutboxPublisher` (in-process), Kafka 구현체는
   skeleton 상태.

6. **Docker Multi-stage + GitHub Actions CI/CD** — Gradle JDK 25 빌더
   스테이지와 Temurin JRE Alpine 런타임 스테이지 분리. CI 는
   paths-filter → build → JaCoCo → SonarCloud → Snyk → GHCR 푸시 체인.

7. **Hexagonal Architecture + ArchUnit 경계 강제** — 10개 도메인을
   port/adapter 로 분리, ArchUnit 규칙 4종 (도메인↛Spring/JPA,
   애플리케이션↛영속성, 크로스 도메인 격리, Port 는 인터페이스) 을
   테스트로 강제.

### 기술

Java 25, Spring Boot 3.5.10, PostgreSQL 17, Elasticsearch 8.17, Spring
Batch, Flyway, Caffeine, iText 8, Micrometer + Prometheus + Grafana,
Docker, GitHub Actions, Kubernetes (ArgoCD + CronJob), JUnit 5 +
Testcontainers + ArchUnit.

### 성과

- **트랜잭션 일관성 확보** — Outbox 도입으로 결제·환불 시 이벤트 유출
  위험 제거.
- **운영 가시성 확보** — 배치/환불/outbox 각 단계에 Prometheus 지표 +
  Alertmanager Slack 룰 연결, p95 지연과 적체를 5분 내 감지.
- **멱등성 3단 계층** — 환불·정산 생성·이벤트 재발행 각각을 별도
  unique 제약으로 격리해 중복 정산 원천 차단.
- **테스트 자동화** — Testcontainers PostgreSQL 17 기반 통합 테스트로
  Flyway 전체 마이그레이션 + Hibernate schema validation 을 CI 에서
  검증. 스키마-엔티티 불일치 버그 자동 감지.

### 향후 확장 (로드맵)

- **Kafka 전환** — `PublishExternalEventPort` Kafka 구현체 + 브로커
  구성. 포트 추상화 덕분에 도메인 코드 변경 없이 스위칭 가능.
- **배치 주 경로의 이벤트 기반화** — Payment CAPTURED 이벤트를 정산
  생성의 직접 트리거로 승격, CronJob 은 재처리 전용으로 격하.
- **Consumer 측 멱등성 테이블** — 다중 컨슈머 환경에서 `processed_events`
  로 event_id × consumer_group 중복 수신 감지.
