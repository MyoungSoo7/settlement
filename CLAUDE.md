# Lemuel — 이커머스 + 정산 MSA 플랫폼

## 프로젝트 개요

주문·결제·정산·시공예약을 **3개 마이크로서비스 + API Gateway** 로 분리한 헥사고날 아키텍처 백엔드.
원래 단일 모놀리스였으나 Bounded Context 분리 + Read-only Projection 패턴으로 MSA 화 함.
자세한 사용자용 문서는 [`README.md`](./README.md) 참조.

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어 | Java 25 |
| 프레임워크 | Spring Boot 4.0.4 |
| 빌드 | Gradle Multi-module (Kotlin DSL) |
| API Gateway | Spring Cloud Gateway 2025 |
| 데이터베이스 | PostgreSQL 17 |
| 검색 엔진 | Elasticsearch 8.17 |
| 메시지 브로커 | Kafka (Redpanda 호환) |
| PG 연동 | Toss Payments |
| 배치 처리 | Spring Batch |
| 캐시 | Caffeine (L1) + 선택적 Redis L2 (2-tier, opt-in) |
| PDF 생성 | iText 8 |
| 모니터링 | Micrometer + Prometheus |
| 마이그레이션 | Flyway (초기 V1~V50, 이후 `V{timestamp}__` 명명 혼재) |
| 회복탄력성 | Resilience4j |
| Rate Limiting | Bucket4j |

## 모듈 구조

```
settlement/                       # Gradle 멀티 모듈 루트
├── settings.gradle.kts           # 5 모듈 선언
├── build.gradle.kts              # 부모 빌드 (subprojects 공통 설정)
├── shared-common/                # 📦 java-library: 전 서비스가 의존
│   └── github.lms.lemuel.common.{audit, config, exception, outbox, ratelimit, pdf}
├── order-service/                # 🛒 Commerce 서비스 (port 8088)
│   └── github.lms.lemuel.{user, order, payment, cart, shipping, product, category, coupon, review, game}
├── settlement-service/           # 💰 Settlement 서비스 (port 8082)
│   └── github.lms.lemuel.{settlement, payout, ledger, chargeback, pgreconciliation, report}
├── reservation-service/          # 🛠 Reservation 서비스 (port 8083, 자체 DB reservations_db)
│   └── github.lms.lemuel.reservation.*
└── gateway-service/              # 🚪 API Gateway (port 8080)
```

### 서비스별 책임

| 서비스 | 패키지 | 책임 |
|--------|--------|------|
| **order-service** | `user, order, payment, cart, shipping, product, category, coupon, review, game` | 회원·상품·장바구니·주문·결제·배송 — 거래 컨텍스트 |
| **settlement-service** | `settlement, payout, ledger, chargeback, pgreconciliation, report` | 정산 생성/확정, 지급(payout), 복식부기 원장(ledger), 차지백, PG 대사, ES 색인, PDF, 캐시플로우 리포트 |
| **reservation-service** | `reservation` | 시공 예약/기사 배정 — 독립 배포 + 자체 DB(reservations_db). 기사 자격은 user 멤버십 이벤트로 동기화되는 로컬 `technician_view` 프로젝션으로 검증(코드·DB 의존 0) |
| **gateway-service** | (Spring Cloud Gateway) | 라우팅, 인증 필터 |
| **shared-common** | `common.*` | 전 서비스 공유 — 감사·관측·예외·Outbox·rate limit·JWT·PDF |

## 헥사고날 아키텍처 (각 서비스 내부)

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/              # 도메인 모델 (POJO)
├── application/
│   ├── port/in/         # 인바운드 포트 (UseCase 인터페이스)
│   ├── port/out/        # 아웃바운드 포트 (영속성/외부 서비스)
│   └── service/         # UseCase 구현
└── adapter/
    ├── in/web/          # REST 컨트롤러
    ├── in/kafka/        # Kafka 컨슈머 (settlement-service)
    ├── in/batch/        # Spring Batch (settlement-service)
    ├── out/persistence/ # JPA 리포지토리, 엔티티
    ├── out/external/    # PG 클라이언트 (Toss)
    ├── out/event/       # Outbox-backed Kafka publisher
    ├── out/readmodel/   # ★ Read-only projection (settlement-service 전용)
    ├── out/search/      # ES 색인
    └── out/pdf/         # iText PDF
```

## ★ Read-only Projection 패턴 (핵심)

`settlement-service` 가 `order-service` 코드를 **import 하지 않으면서** Order/Payment/User/Product
데이터를 조회하기 위한 분리 기법. settlement-service 자체에 `@Immutable JpaEntity` 정의 →
같은 테이블 매핑 → 코드 의존성 0.

```
settlement-service/.../adapter/out/readmodel/
├── SettlementPaymentReadModel    (payments 테이블 read-only)
├── SettlementOrderReadModel      (orders 테이블)
├── SettlementUserReadModel       (users 테이블, email만)
├── SettlementProductReadModel    (products 테이블, name만)
└── *Repository                   (Spring Data JPA)
```

→ `settlement-service/build.gradle.kts` 에 `implementation(project(":order-service"))` **없음**.
→ MSA 의 코드 경계 100% 확립.

## 도메인 규칙

### Payment 상태
```
READY → AUTHORIZED → CAPTURED → REFUNDED
              ↘ FAILED        ↘ CANCELED (승인취소)
```

### Settlement 상태
```
REQUESTED → PROCESSING → DONE
                       → FAILED
                       → CANCELED
```

### Order 상태
```
CREATED → PAID → REFUNDED
              → CANCELED
```
실제 enum 은 더 세분화된 라이프사이클 보유:
`ORDER_PLACED, PAYMENT_COMPLETED, SHIPPING_PENDING, IN_TRANSIT, DELIVERED,
CANCELLATION_REQUESTED/APPROVED, REFUND_REQUESTED/COMPLETED` (배송·취소·환불 단계)

### Reservation 상태 (시공 예약)
```
REQUESTED → CONFIRMED → ASSIGNED → IN_PROGRESS → COMPLETED
(접수)      (관리자확인)  (기사배정)   (시공중)       (시공완료)
                                              → CANCELED
```
업체회원이 예약 등록(REQUESTED) → 관리자 확인 → 시공기사 배정/재배정 → 진행/완료.
엔드포인트 `/reservations/**` (reservation-service:8083, gateway 라우팅됨).

### Payout 상태 (셀러 지급)
```
REQUESTED → SENDING → COMPLETED
                    → FAILED
                    → CANCELED
```

### Chargeback 상태 (지급 거절/분쟁)
```
OPEN → ACCEPTED
     → REJECTED
```

### Ledger 상태 (복식부기 원장)
```
PENDING → POSTED → REVERSED
```

### PG 대사 실행 상태 (PgReconciliation)
```
RUNNING → COMPLETED
        → FAILED
```

### 수수료
- 셀러 등급별 차등 수수료율 (V32 `commission_rate` 스냅샷, `SellerTier`):
  **NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%**
  (레거시 기본 3% 는 `Settlement.COMMISSION_RATE` 상수로만 보존 — 운영 rate 는 등급 기준)
- 정산 시점의 `commission_rate` 영구 보존 (이력 보존 — 추후 요율 변경 영향 없음)
- 정산 주기도 등급별 차등 (`SellerTier.defaultCycle`, `users.settlement_cycle` 우선):
  **NORMAL T+7 / VIP T+3 / STRATEGIC T+1** 영업일
- 홀드백(holdback) 정책: `HoldbackPolicy.forTier` —
  **NORMAL 30%/30일, VIP 10%/14일, STRATEGIC 0%** 보류 후 `holdbackReleaseDate` 에 해제

## 이벤트 흐름 (Outbox + Kafka)

```
[order-service] Payment.capture() (DB tx)
    ├─ payments.status = CAPTURED
    └─ outbox_events INSERT (PaymentCaptured)
                     ↓ (멀티워커 폴러 — FOR UPDATE SKIP LOCKED claim, 기본 2s, 비동기 배치 발행)
                 Kafka topic: lemuel.payment.captured
                     ↓
[settlement-service] PaymentEventKafkaConsumer
    ├─ processed_events (group, event_id) PK 멱등 체크
    └─ Settlement.createFromPayment() (DB tx)
```

**3단 멱등 방어**:
1. `outbox_events.event_id UUID UNIQUE`
2. `processed_events PK (consumer_group, event_id)`
3. `settlements.payment_id UNIQUE`

## 코딩 컨벤션

- **아키텍처**: 헥사고날 (Ports & Adapters)
- **도메인 모델**: 순수 POJO, 프레임워크 의존성 없음
- **포트/어댑터**: in/out 명확히 분리
- **DB 마이그레이션**: Flyway, 초기 V1~V50 + `V{timestamp}__` 명명 혼재 (예: `V20260611110000__`). 신규는 timestamp 명명 권장
- **테스트**: 도메인 단위 → 서비스 → 컨트롤러 → 통합 순
- **헥사고날 강제**: ArchUnit 으로 패키지 의존 방향 검증
- **MSA 경계**: settlement-service ↔ order-service 코드 의존 0 (read-model 또는 Kafka 이벤트로만)

## 인프라

- **컨테이너**: Docker Compose (로컬), Kubernetes (운영)
- **리버스 프록시**: gateway-service (Spring Cloud Gateway)
- **모니터링**: Prometheus + Micrometer + Grafana
- **메시지 브로커**: Redpanda (Kafka 호환)
- **코드 커버리지**: JaCoCo — CI 게이트 LINE 최소 50%, 핵심 도메인 패키지(payment/order/product/settlement/... domain) INSTRUCTION 80% 강제 (`build.gradle.kts`). adapter in/out 서브패키지 전반은 게이트에서 제외(통합 테스트로 별도 검증)

## 보안

| 항목 | 설정 |
|------|------|
| 인증 | JWT (HS256) — `shared-common.common.config.jwt` |
| 비밀번호 해싱 | BCrypt (cost=12) |
| CORS | 환경변수 화이트리스트 |
| Rate Limiting | Bucket4j (`shared-common.common.ratelimit`) |
| Actuator | 인증 필수, 비인가 접근 차단 |
| Audit | PII 마스킹 + 감사 로그 (`shared-common.common.audit`) |
| 환불 동시성 | Pessimistic Lock + Idempotency-Key |

## 빌드 및 실행 커맨드

```bash
# 전체 빌드
./gradlew build

# 모듈별 컴파일
./gradlew :shared-common:compileJava
./gradlew :order-service:compileJava
./gradlew :settlement-service:compileJava
./gradlew :gateway-service:compileJava

# 모듈별 테스트
./gradlew :order-service:test
./gradlew :settlement-service:test

# 모듈별 부트런
./gradlew :order-service:bootRun
./gradlew :settlement-service:bootRun
./gradlew :gateway-service:bootRun

# 모듈별 jar
./gradlew :order-service:bootJar

# Docker Compose
docker compose up -d                # PG + ES + Redpanda + 3 services
docker compose down

# 컨테이너 이미지 (MODULE 빌드 인자로 어떤 서비스인지 지정)
docker build --build-arg MODULE=order-service       -t lemuel-order .
docker build --build-arg MODULE=settlement-service  -t lemuel-settlement .
docker build --build-arg MODULE=gateway-service     -t lemuel-gateway .
```

## 작업 이력 / 브랜치 정보

- **메인 라인**: `develop` → `main`
- **MSA 분리**: 완료됨 (4-module + Read-only Projection 으로 settlement↔order 코드 의존 0).
  분리 전 백업은 `backup/pre-msa-split`.
- **이후 추가 도메인**: `reservation`(시공 예약/기사 배정), 멤버십 승인 등.
- **TPS 개선 작업**: PgBouncer, Read Replica 라우팅(opt-in), JDBC 배치, Outbox 비동기 배치 +
  SKIP LOCKED 멀티워커, Kafka 컨슈머 병렬화, Redis 2-tier 캐시(opt-in). 상세는 [`docs/tps.md`](./docs/tps.md).
