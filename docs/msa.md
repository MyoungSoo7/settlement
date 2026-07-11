# MSA(마이크로서비스 아키텍처) 설명 및 본 프로젝트 적용 분석

이 문서는 두 부분으로 구성된다.

1. **MSA 개념 설명** — 마이크로서비스란 무엇이고 왜/언제 쓰는가
2. **본 프로젝트(Lemuel) 적용 분석** — 코드 근거로 본 "지향한 것 / 실제 구현된 것 / 아직 모놀리스인 것"

> 결론을 먼저 말하면, 이 프로젝트는 **"코드 경계 100% 분리에 더해 DB-per-service·서비스별 독립 배포까지
> 완료한 물리 MSA"** 다. order↔settlement 도 ADR 0020 으로 DB 물리 분리(이벤트 CQRS 프로젝션)를 끝냈다.

---

# 1부. MSA란 무엇인가

## 1.1 정의

**마이크로서비스 아키텍처(MSA)** 는 하나의 애플리케이션을, **독립적으로 배포 가능한 작은
서비스들의 집합**으로 구성하는 방식이다. 각 서비스는

- 하나의 **비즈니스 능력(Business Capability)** 에 집중하고,
- **자체 데이터를 소유**(Database per Service)하며,
- **네트워크(HTTP/메시지)** 로만 통신하고,
- **독립적으로 배포·확장·장애격리**된다.

## 1.2 모놀리스 vs MSA

| 구분 | 모놀리스 | MSA |
|------|----------|-----|
| 배포 단위 | 1개의 큰 산출물 | 서비스별 독립 배포 |
| 코드 결합 | 직접 메서드 호출 | 네트워크/이벤트 |
| 데이터 | 단일 공유 DB | 서비스별 DB 소유 |
| 확장 | 전체를 통째로 | 병목 서비스만 |
| 장애 | 전체 전파 위험 | 격리 가능 |
| 트랜잭션 | 로컬 ACID | 분산 트랜잭션(Saga 등) |
| 팀 구조 | 한 팀이 전체 | 서비스별 팀(Conway) |
| 단점 | 거대화·배포 부담 | 운영 복잡도·일관성 난이도↑ |

## 1.3 핵심 원칙·패턴

- **Bounded Context (DDD)**: 도메인을 응집도 높은 경계로 나눈다. MSA의 서비스 분리 기준.
- **API Gateway**: 클라이언트 단일 진입점. 라우팅·인증·rate limit을 한곳에서.
- **Database per Service**: 서비스가 자기 DB를 소유. 다른 서비스가 직접 접근 금지.
- **비동기 이벤트 기반 통신**: 동기 호출의 강결합·장애전파를 줄이기 위해 메시지 브로커 사용.
- **Saga / 최종 일관성(Eventual Consistency)**: 분산 환경에서 2PC 대신 이벤트로 보상 트랜잭션.
- **Transactional Outbox**: DB 트랜잭션과 메시지 발행의 원자성을 보장(이중 쓰기 문제 해결).
- **멱등성(Idempotency)**: 메시지 재전송(at-least-once)에 대비해 중복 처리 방지.
- **장애 격리**: Circuit Breaker / Bulkhead / Timeout (Resilience4j 등).

## 1.4 언제 MSA가 적합한가 / 과한가

**적합**: 도메인이 크고 팀이 여럿, 서비스별 확장 요구가 다르고, 독립 배포 속도가 중요할 때.
**과함**: 초기 스타트업/소규모 도메인 — 분산의 운영 비용(관측·배포·일관성)이 이득을 초과.
→ 그래서 실무 정석은 **"모듈러 모놀리스로 시작해 경계가 검증되면 떼어낸다"**. 본 프로젝트가
정확히 이 전략을 택했다(아래 분석 참고).

---

# 2부. 본 프로젝트(Lemuel)의 MSA 적용 분석

## 2.1 전체 구성

이커머스(주문·결제) + 정산 + 위성 도메인을 분리한 Gradle **멀티모듈** 구조다.
(`settings.gradle.kts` 기준 13개 모듈 — 12 서비스 + gateway — 에 shared-common composite build)

```
lemuel/
├── shared-common/      📦 공통(JWT·Outbox·감사·rate limit·PDF) — 버전드 내부 라이브러리(composite build)
├── order-service/      🛒 거래 컨텍스트 (user·order·payment·cart·shipping·product·coupon …) :8088 / opslab
├── settlement-service/ 💰 정산 컨텍스트 (settlement·payout·ledger·chargeback·pgreconciliation·report) :8082 / settlement_db
├── loan-service/       💸 선정산·기업대출 :8084 / lemuel_loan
├── (위성 9종)          financial · economics · company · operation · market · ai · common-data · investment · account — 각자 자체 DB
└── gateway-service/    🚪 Spring Cloud Gateway (WebFlux, 단일 진입점 :8080)
```

| 서비스 | Bounded Context | 책임 |
|--------|----------------|------|
| order-service | 거래 | 회원·상품·장바구니·주문·결제·배송 |
| settlement-service | 정산 | 정산 생성/확정, 지급(payout), 복식부기 원장(ledger), 차지백, PG 대사, ES 색인, PDF |
| gateway-service | — | 라우팅, 인증 필터 |

## 2.2 ✅ 진짜 MSA급으로 구현된 것

### (1) Bounded Context 분리 + 코드 의존성 0

가장 잘 된 부분이다. **settlement-service가 order-service를 컴파일 의존하지 않는다**
(`settlement-service/build.gradle.kts` 에 `implementation(project(":order-service"))` 없음).
거래 컨텍스트와 정산 컨텍스트가 코드 레벨에서 완전히 끊겨 있다.

### (2) ★ 이벤트 드리븐 프로젝션 패턴 (CQRS, ADR 0020)

정산 서비스가 order-service 코드를 import하지 않고 **DB도 공유하지 않으면서** Order/Payment/User/Product를
조회하기 위해, **자체 DB(settlement_db)에 소유하는 프로젝션 테이블**에 order 가 발행한 Kafka 이벤트를
컨슈머가 받아 적재한다.

```java
// settlement-service/.../adapter/out/readmodel/SettlementPaymentViewJpaEntity.java
@Entity @Table(name = "settlement_payment_view")   // settlement_db 소유, Kafka 이벤트로 적재
public class SettlementPaymentViewJpaEntity { ... }
```

→ 대사(reconciliation)는 order 의 내부 API(`/internal/recon`)를 공유 시크릿으로 호출해 합계를 비교한다.
양측 모두 자기 DB 만 읽어 cross-DB 연결 0. (= 진짜 Database-per-Service, ADR 0020 완료 — 2.4 참고)

### (3) API Gateway (Spring Cloud Gateway, reactive)

`gateway-service/application.yml` 에 경로 기반 라우팅이 정의돼 있다.

```yaml
- id: order-service-orders
  predicates: [Path=/api/orders/**, /api/payments/**, /api/products/** ...]
- id: settlement-service
  predicates: [Path=/api/settlements/**, /api/reports/**, /api/ledger/**, /admin/payouts/** ...]
```

클라이언트는 `:8080` 단일 진입점만 알면 되고, 경로로 서비스가 결정된다.

### (4) 비동기 이벤트 기반 통합 (Outbox + Kafka)

두 컨텍스트는 **동기 호출이 아니라 이벤트로** 연결된다.

```
[order-service] Payment.capture() (1 DB tx)
   ├─ payments.status = CAPTURED
   └─ outbox_events INSERT (PaymentCaptured)      ← 이중 쓰기 문제를 Outbox로 해결
            ↓ OutboxPublisherScheduler (2초 폴링, SKIP LOCKED claim)
        Kafka: lemuel.payment.captured
            ↓
[settlement-service] PaymentEventKafkaConsumer → Settlement.createFromPayment()
```

→ **Transactional Outbox** 로 DB 커밋과 메시지 발행의 원자성을 보장하고, 컨텍스트 간 시간적
**최종 일관성**을 택했다. 이는 교과서적 MSA 통합 패턴이다.

### (5) 멱등성 3단 방어 (at-least-once 대비)

Kafka는 적어도 1번(at-least-once) 전달이라 중복이 발생한다. 이에 대해:

1. `outbox_events.event_id UUID UNIQUE`
2. `processed_events PK (consumer_group, event_id)` — 컨슈머측 중복 차단
3. `settlements.payment_id UNIQUE` — 최종 방어

### (6) 헥사고날 + ArchUnit으로 경계 강제

각 서비스 내부가 Ports & Adapters로 정리되고, **ArchUnit 테스트로 의존 방향을 컴파일 타임에
검증**한다(도메인→프레임워크 의존 금지, 어댑터→도메인 단방향). 경계가 "문서상 약속"이 아니라
테스트로 강제된다.

### (7) 장애 격리·회복탄력성 요소

Resilience4j(PG 연동 Circuit Breaker/Bulkhead), Bucket4j rate limit, Kafka DLT(Dead Letter
Topic) + 재처리(`DlqReplayService`) 등 MSA 운영에 필요한 요소들이 갖춰져 있다.

## 2.3 인프라 관점 (docker-compose 기준)

| 구성요소 | 역할 |
|----------|------|
| PostgreSQL 17 | 데이터 저장 (**서비스별 DB 물리 분리** — opslab · settlement_db · lemuel_loan 등 12종 컨테이너) |
| Redpanda | Kafka 호환 브로커 (이벤트 백본) |
| Elasticsearch 8.17 | 정산 검색/집계 색인 |
| Redis | 장바구니 + L2 캐시(Pub/Sub 무효화) |
| PgBouncer | 트랜잭션 풀링 (커넥션 멀티플렉싱) |
| Prometheus + Tempo + Grafana | 메트릭 + **분산 트레이싱** + 대시보드 |

→ 관측성(분산 트레이싱 포함)은 MSA 운영 수준으로 갖춰져 있다.

## 2.4 ✅ 물리 MSA 전환 완료 (과거 과도기 대비)

초기에는 settlement 가 order 의 fat jar 에 번들되고(library mode) 단일 DB 를 공유하던 과도기가 있었으나,
현재는 아래처럼 **프로세스·DB·배포가 모두 분리**됐다.

### (1) settlement-service 독립 배포 — Standalone

```kotlin
// settlement-service/build.gradle.kts (ADR 0020 Phase 0)
// "Standalone 모드: settlement-service 는 자체 실행가능 jar 로 독립 기동(:8082).
//  진입점은 SettlementServiceApplication." — bootJar 는 Spring Boot 플러그인 기본값(활성)
```

settlement-service는 **자체 부팅 가능한 산출물**로, 별도 컨테이너·별도 프로세스에서 :8082 로 돈다.

### (2) Gateway가 정산 트래픽을 settlement-service로 보낸다

```yaml
# docker-compose.yml (gateway-service)
ORDER_SERVICE_URI:      http://order-service:8080
SETTLEMENT_SERVICE_URI: http://settlement-service:8080   # ← 정산은 실제 settlement 인스턴스로
```

게이트웨이 라우팅 규칙이 구분한 대로, 정산 엔드포인트는 **독립된 settlement-service 프로세스**가 처리한다.

### (3) Database per Service 적용 — 서비스별 자체 DB

두 컨텍스트가 물리적으로 다른 PostgreSQL 인스턴스를 소유한다(order=opslab, settlement=settlement_db).
정산이 order 데이터를 조회할 때는 **order 가 발행한 Kafka 이벤트를 settlement 자체 `settlement_*_view`
프로젝션 테이블에 복제**해 읽는다(cross-DB 연결 0). 대사는 order 내부 API(`/internal/recon`)로 처리한다.

### (4) 결과: 독립 배포/독립 확장/장애 격리(프로세스 수준) 달성

서비스별 프로세스·DB 가 분리돼 있어 MSA 의 핵심 이득인 *서비스별 독립 배포, 병목 서비스만 스케일,
프로세스 장애 격리, DB 독립 진화* 를 확보했다.

## 2.5 MSA 성숙도 평가 요약

| 평가 축 | 상태 | 근거 |
|---------|------|------|
| Bounded Context 분리 | ✅ 우수 | 컨텍스트별 모듈, 코드 의존성 0 |
| 코드 결합도 | ✅ 우수 | read-model + Kafka, ArchUnit 강제 |
| 비동기 이벤트 통합 | ✅ 우수 | Outbox + Kafka + 멱등 3단 |
| API Gateway | ✅ 적용 | Spring Cloud Gateway 경로 라우팅 |
| 관측성 | ✅ 적용 | Prometheus + Tempo 분산 트레이싱 |
| 회복탄력성 | 🟢 적용 | Resilience4j/DLT + 서비스 물리 분리로 프로세스 장애 격리 실효화 |
| 독립 배포 | ✅ 달성 | settlement standalone(자체 bootJar·별도 컨테이너 :8082) |
| Database per Service | ✅ 달성 | 서비스별 자체 DB (order=opslab, settlement=settlement_db 등) |
| 독립 확장/장애격리 | ✅ 달성 | 서비스별 독립 프로세스 |

→ **논리적 분해(decomposition)와 물리적 배포(deployment)를 모두 MSA 수준으로 완료.** 경계를 코드로
먼저 검증하고(되돌리기 쉬움) 운영 비용이 정당화되는 시점에 물리 분리한다는 1.4 의 정석 전략을 그대로 밟았다.

## 2.6 물리 MSA 전환 로드맵 — 완료 현황

과거 "Phase B"로 예고했던 작업들의 현재 상태:

1. ✅ **독립 배포 복원**: settlement-service `bootJar` 활성 → 별도 컨테이너/포트(:8082)로 기동,
   gateway `SETTLEMENT_SERVICE_URI` 를 실제 settlement 인스턴스로 분리 (ADR 0020 Phase 0).
2. ✅ **Database per Service**: settlement 전용 DB(settlement_db) 분리. read-model을 **Kafka 이벤트로 적재된
   자체 `settlement_*_view` 프로젝션 테이블**로 전환, 대사는 order 내부 API 로 처리 (ADR 0020 완료).
3. 🟡 **CI/CD·Helm 분리**: 서비스별 컨테이너 이미지·게이트웨이 라우팅 분리 완료, 파이프라인 완전 분리는 진행 과제.
4. ✅ **회복탄력성 실효화**: 서비스가 물리 분리돼 Circuit Breaker/Bulkhead 가 실제 장애전파를 막는다.

---

## 부록 — 관련 문서

- 아키텍처 결정 기록: `docs/adr/` (특히 `0001-hexagonal-architecture`, `0003-transactional-outbox-pattern`, `0009-boot4-migration-module-split`)
- 처리량 개선: `docs/tps.md` (Outbox 멀티워커, Kafka 컨슈머 병렬화, 2-tier 캐시 등)
- 이벤트 흐름 시퀀스: `docs/diagrams/sequence-payment-to-settlement.md`
- 프로젝트 규약 전반: `CLAUDE.md`

> **요약 한 줄**: Lemuel은 *"MSA의 어려운 절반(경계·이벤트·멱등·관측)을 먼저 코드로 해결한 뒤,
> 나머지 절반(프로세스·DB 물리 분리)까지 ADR 0020 으로 완료한"* 실용적 MSA 전환 프로젝트다.
