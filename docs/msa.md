# MSA(마이크로서비스 아키텍처) 설명 및 본 프로젝트 적용 분석

이 문서는 두 부분으로 구성된다.

1. **MSA 개념 설명** — 마이크로서비스란 무엇이고 왜/언제 쓰는가
2. **본 프로젝트(Lemuel) 적용 분석** — 코드 근거로 본 "지향한 것 / 실제 구현된 것 / 아직 모놀리스인 것"

> 결론을 먼저 말하면, 이 프로젝트는 **"MSA를 지향해 코드 경계를 100% 분리했으나, 현재 런타임은
> 단일 배포 + 단일 공유 DB인 모듈러 모놀리스"** 다. 즉 *논리적 MSA는 완성, 물리적 MSA는 전환 중*.

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

이커머스(주문·결제) + 정산을 분리한 Gradle **멀티모듈** 구조다.
(`settings.gradle.kts` 기준 4개 모듈)

```
lemuel/
├── shared-common/      📦 공통(JWT·Outbox·감사·rate limit·PDF) — 양 서비스가 의존
├── order-service/      🛒 거래 컨텍스트 (user·order·payment·cart·shipping·product·coupon …)
├── settlement-service/ 💰 정산 컨텍스트 (settlement·payout·ledger·chargeback·pgreconciliation·report)
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

### (2) ★ Read-only Projection 패턴

정산 서비스가 order-service 코드를 import하지 않고도 Order/Payment/User/Product를 조회하기 위해,
**자체 `@Immutable` JPA 엔티티로 같은 테이블을 읽기 전용 매핑**한다.

```java
// settlement-service/.../adapter/out/readmodel/SettlementPaymentReadModel.java
@Entity @Immutable @Table(name = "payments")   // order-service가 쓰는 테이블을 읽기만 함
public class SettlementPaymentReadModel { ... }
```

→ 클래스 주석이 정직하게 밝히듯, **"물리 DB는 단일 PG를 공유(포트폴리오 간소화)"**. 운영에서는
Outbox+Kafka로 정산 서비스 자체 테이블에 복제하는 형태로 확장 가능. (= 현재는 *공유 DB 위의
논리적 경계*, 진짜 Database-per-Service는 아님 — 2.4 참고)

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
| PostgreSQL 17 | 데이터 저장 (**단일 `inter` DB 공유**) |
| Redpanda | Kafka 호환 브로커 (이벤트 백본) |
| Elasticsearch 8.17 | 정산 검색/집계 색인 |
| Redis | 장바구니 + L2 캐시(Pub/Sub 무효화) |
| PgBouncer | 트랜잭션 풀링 (커넥션 멀티플렉싱) |
| Prometheus + Tempo + Grafana | 메트릭 + **분산 트레이싱** + 대시보드 |

→ 관측성(분산 트레이싱 포함)은 MSA 운영 수준으로 갖춰져 있다.

## 2.4 ⚠️ 아직 MSA가 아닌 부분 (정직한 평가)

겉모습은 멀티서비스지만, **현재 런타임 형태는 모듈러 모놀리스에 가깝다**. 근거:

### (1) settlement-service가 독립 배포되지 않는다 — Library Mode

```kotlin
// settlement-service/build.gradle.kts
// "Library mode: settlement-service 는 order-service 의 fat jar 에 번들된다.
//  MSA 분리 배포(원래 의도)는 Phase B 에서 helm/CI 분리와 함께 재도입 예정."
tasks.named<BootJar>("bootJar") { enabled = false }   // ← 독립 실행 jar 비활성
```

settlement-service는 **자체 부팅 가능한 산출물이 아니라 라이브러리**로, order-service에 포함되어
한 프로세스에서 돈다.

### (2) Gateway가 정산 트래픽을 order-service로 보낸다

```yaml
# docker-compose.yml (gateway-service)
ORDER_SERVICE_URI:      http://order-service:8080
SETTLEMENT_SERVICE_URI: http://order-service:8080   # ← 정산도 결국 order-service로
```

게이트웨이 라우팅 규칙은 두 서비스를 구분하지만, 실제 목적지 URI는 **둘 다 order-service**다.
정산 엔드포인트도 order-service 프로세스가 처리한다. (compose에 settlement-service 컨테이너
정의는 남아 있으나, bootJar 비활성으로 인한 과도기적 불일치.)

### (3) Database per Service 미적용 — 단일 공유 DB

두 컨텍스트가 같은 PostgreSQL `inter` DB의 같은 테이블을 본다(read-model이 그 증거).
**서비스별 DB 소유**라는 MSA 핵심 원칙은 아직 적용 전이다. 따라서 "정산이 결제 테이블을 읽는다"가
지금은 같은 DB 조회로 동작하지만, 진짜 MSA에서는 이벤트로 복제된 자체 테이블이어야 한다.

### (4) 결과: 독립 배포/독립 확장/장애 격리(프로세스 수준) 미달

한 프로세스·한 DB이므로, MSA의 핵심 이득인 *서비스별 독립 배포, 병목 서비스만 스케일,
프로세스 장애 격리, DB 독립 진화* 는 아직 얻지 못한다.

## 2.5 MSA 성숙도 평가 요약

| 평가 축 | 상태 | 근거 |
|---------|------|------|
| Bounded Context 분리 | ✅ 우수 | 컨텍스트별 모듈, 코드 의존성 0 |
| 코드 결합도 | ✅ 우수 | read-model + Kafka, ArchUnit 강제 |
| 비동기 이벤트 통합 | ✅ 우수 | Outbox + Kafka + 멱등 3단 |
| API Gateway | ✅ 적용 | Spring Cloud Gateway 경로 라우팅 |
| 관측성 | ✅ 적용 | Prometheus + Tempo 분산 트레이싱 |
| 회복탄력성 | 🟡 부분 | Resilience4j/DLT 있음, 서비스 분리 전이라 효과 제한 |
| 독립 배포 | ❌ 미달 | settlement library-mode (order에 번들) |
| Database per Service | ❌ 미달 | 단일 `inter` DB 공유 |
| 독립 확장/장애격리 | ❌ 미달 | 단일 프로세스 |

→ **논리적 분해(decomposition)는 MSA 수준, 물리적 배포(deployment)는 모놀리스.** 이는 흠이
아니라 **합리적 전략**이다 — 경계를 코드로 먼저 검증하고(되돌리기 쉬움), 운영 비용이 정당화될 때
물리 분리한다(1.4의 정석).

## 2.6 진짜 MSA로 가는 로드맵 (Phase B)

코드 주석들이 "Phase B"로 예고한 작업들:

1. **독립 배포 복원**: settlement-service `bootJar` 재활성 → 별도 컨테이너/포트(:8082)로 기동,
   gateway `SETTLEMENT_SERVICE_URI` 를 실제 settlement 인스턴스로 분리.
2. **Database per Service**: 정산 전용 DB 분리. read-model을 **Kafka 이벤트로 복제된 자체
   테이블**로 전환(현재 공유 테이블 직접 매핑 → 이벤트 소싱 프로젝션).
3. **CI/CD·Helm 분리**: 서비스별 파이프라인·차트로 독립 릴리스.
4. **회복탄력성 실효화**: 서비스가 물리 분리되면 Circuit Breaker/Bulkhead가 실제 장애전파를 막는다.

---

## 부록 — 관련 문서

- 아키텍처 결정 기록: `docs/adr/` (특히 `0001-hexagonal-architecture`, `0003-transactional-outbox-pattern`, `0009-boot4-migration-module-split`)
- 처리량 개선: `docs/tps.md` (Outbox 멀티워커, Kafka 컨슈머 병렬화, 2-tier 캐시 등)
- 이벤트 흐름 시퀀스: `docs/diagrams/sequence-payment-to-settlement.md`
- 프로젝트 규약 전반: `CLAUDE.md`

> **요약 한 줄**: Lemuel은 *"MSA의 어려운 절반(경계·이벤트·멱등·관측)을 먼저 코드로 해결하고,
> 쉬운 절반(프로세스·DB 물리 분리)은 비용이 정당화될 때로 미룬"* 실용적 MSA 전환 프로젝트다.
